/**
 * Unit tests for the outbound brain-call mappers (`fetchTools` /
 * `postToolCallback`), covering the outcome unions without pi-ai or SSE.
 */

import { createServer, type IncomingMessage, type Server } from "node:http";
import { afterAll, describe, expect, it, vi } from "vitest";
import { fetchTools, postToolCallback, type ToolCallbackPayload } from "../src/toolTransport.js";

interface CapturedRequest {
  method: string;
  url: string;
  headers: Record<string, string | string[] | undefined>;
  body: string;
}

interface ScriptedServer {
  url: string;
  requests: () => CapturedRequest[];
  close: () => Promise<void>;
}

type Response = { status: number; body: unknown } | { status: number; rawBody: string } | "hang";

function startScriptedServer(respond: () => Response): Promise<ScriptedServer> {
  const requests: CapturedRequest[] = [];
  const server: Server = createServer((req: IncomingMessage, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (chunk: Buffer) => chunks.push(chunk));
    req.on("end", () => {
      requests.push({
        method: req.method ?? "?",
        url: req.url ?? "/",
        headers: req.headers,
        body: Buffer.concat(chunks).toString("utf8"),
      });
      const outcome = respond();
      if (outcome === "hang") {
        // never answer; the caller aborts
        return;
      }
      res.writeHead(outcome.status, { "content-type": "application/json" });
      res.end("rawBody" in outcome ? outcome.rawBody : JSON.stringify(outcome.body));
    });
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (address === null || typeof address === "string") {
        reject(new Error("scripted server failed to bind"));
        return;
      }
      resolve({
        url: `http://127.0.0.1:${address.port}`,
        requests: () => [...requests],
        close: () =>
          new Promise<void>((resolveClose, rejectClose) => {
            server.close((error) => (error ? rejectClose(error) : resolveClose()));
          }),
      });
    });
  });
}

const servers: ScriptedServer[] = [];
afterAll(async () => {
  for (const server of servers) {
    await server.close();
  }
});

async function start(respond: () => Response): Promise<ScriptedServer> {
  const server = await startScriptedServer(respond);
  servers.push(server);
  return server;
}

const TOOLS_BODY = { tools: [{ name: "search", description: "search", schema: { type: "object" } }] };

const PAYLOAD: ToolCallbackPayload = {
  runId: "run-1",
  id: "call_1",
  name: "search",
  args: { query: "hello" },
};

describe("fetchTools", () => {
  it("queries with the runId appended and the token header, and validates the tool list", async () => {
    const server = await start(() => ({ status: 200, body: TOOLS_BODY }));
    const outcome = await fetchTools(`${server.url}/tools`, "run-1", "tok", new AbortController().signal);
    expect(outcome).toEqual({
      kind: "ok",
      tools: [{ name: "search", description: "search", schema: { type: "object" } }],
    });
    const [request] = server.requests();
    expect(request?.url).toBe("/tools?runId=run-1");
    expect(request?.method).toBe("GET");
    expect(request?.headers["x-daapu-token"]).toBe("tok");
  });

  it("maps a non-200 listing onto failure", async () => {
    const server = await start(() => ({ status: 500, body: { error: "boom" } }));
    const outcome = await fetchTools(`${server.url}/tools`, "run-1", "tok", new AbortController().signal);
    expect(outcome).toEqual({ kind: "failure", message: "tool listing returned HTTP 500" });
  });

  it("maps a non-JSON listing body onto failure", async () => {
    const server = await start(() => ({ status: 200, rawBody: "not-json{" }));
    const outcome = await fetchTools(`${server.url}/tools`, "run-1", "tok", new AbortController().signal);
    expect(outcome).toEqual({ kind: "failure", message: "tool listing returned a non-JSON body" });
  });

  it("maps a body without a tools array onto failure", async () => {
    const server = await start(() => ({ status: 200, body: { nope: 1 } }));
    const outcome = await fetchTools(`${server.url}/tools`, "run-1", "tok", new AbortController().signal);
    expect(outcome).toEqual({ kind: "failure", message: "tool listing returned an invalid body" });
  });

  it("maps invalid tool entries onto failure", async () => {
    const server = await start(() => ({
      status: 200,
      body: { tools: [{ name: "search", description: "search" }] },
    }));
    const outcome = await fetchTools(`${server.url}/tools`, "run-1", "tok", new AbortController().signal);
    expect(outcome.kind).toBe("failure");
    if (outcome.kind === "failure") {
      expect(outcome.message).toContain("tool listing returned invalid tools");
    }
  });

  it("aborts when the signal fires while the listing hangs", async () => {
    const server = await start(() => "hang");
    const controller = new AbortController();
    const pending = fetchTools(`${server.url}/tools`, "run-1", "tok", controller.signal);
    await vi.waitFor(() => expect(server.requests()).toHaveLength(1));
    controller.abort();
    await expect(pending).resolves.toEqual({ kind: "abort" });
  });
});

describe("postToolCallback", () => {
  it("posts the payload as JSON with the token header and returns the parts", async () => {
    const server = await start(() => ({
      status: 200,
      body: { parts: [{ type: "text", text: "hi" }], isError: false },
    }));
    const outcome = await postToolCallback(`${server.url}/api/hand/tool`, "tok", PAYLOAD, new AbortController().signal);
    expect(outcome).toEqual({ kind: "ok", parts: [{ type: "text", text: "hi" }], isError: false });
    const [request] = server.requests();
    expect(request?.method).toBe("POST");
    expect(request?.headers["x-daapu-token"]).toBe("tok");
    expect(request?.headers["content-type"]).toBe("application/json");
    expect(JSON.parse(request?.body ?? "{}")).toEqual(PAYLOAD);
  });

  it("maps isError through", async () => {
    const server = await start(() => ({
      status: 200,
      body: { parts: [{ type: "text", text: "exploded" }], isError: true },
    }));
    const outcome = await postToolCallback(`${server.url}/api/hand/tool`, "tok", PAYLOAD, new AbortController().signal);
    expect(outcome).toMatchObject({ kind: "ok", isError: true });
  });

  it("maps a fatal response onto transport_failure with its message", async () => {
    const server = await start(() => ({ status: 200, body: { fatal: { message: "tool exploded" } } }));
    const outcome = await postToolCallback(`${server.url}/api/hand/tool`, "tok", PAYLOAD, new AbortController().signal);
    expect(outcome).toEqual({ kind: "transport_failure", message: "tool exploded" });
  });

  it("maps a malformed fatal onto transport_failure with the default message", async () => {
    const server = await start(() => ({ status: 200, body: { fatal: 5 } }));
    const outcome = await postToolCallback(`${server.url}/api/hand/tool`, "tok", PAYLOAD, new AbortController().signal);
    expect(outcome).toEqual({
      kind: "transport_failure",
      message: "tool callback reported a fatal error",
    });
  });

  it("maps a non-200 onto transport_failure", async () => {
    const server = await start(() => ({ status: 503, body: { error: "down" } }));
    const outcome = await postToolCallback(`${server.url}/api/hand/tool`, "tok", PAYLOAD, new AbortController().signal);
    expect(outcome).toEqual({ kind: "transport_failure", message: "tool callback returned HTTP 503" });
  });

  it("maps a non-JSON body onto transport_failure", async () => {
    const server = await start(() => ({ status: 200, rawBody: "<html>" }));
    const outcome = await postToolCallback(`${server.url}/api/hand/tool`, "tok", PAYLOAD, new AbortController().signal);
    expect(outcome).toEqual({ kind: "transport_failure", message: "tool callback returned a non-JSON body" });
  });

  it("maps a non-object body onto transport_failure", async () => {
    const server = await start(() => ({ status: 200, body: [1, 2] }));
    const outcome = await postToolCallback(`${server.url}/api/hand/tool`, "tok", PAYLOAD, new AbortController().signal);
    expect(outcome).toEqual({ kind: "transport_failure", message: "tool callback returned a non-object body" });
  });

  it("maps invalid parts onto transport_failure", async () => {
    const server = await start(() => ({ status: 200, body: { parts: "nope" } }));
    const outcome = await postToolCallback(`${server.url}/api/hand/tool`, "tok", PAYLOAD, new AbortController().signal);
    expect(outcome).toEqual({ kind: "transport_failure", message: "tool callback returned invalid parts" });
  });

  it("aborts when the signal fires mid-call", async () => {
    const server = await start(() => "hang");
    const controller = new AbortController();
    const pending = postToolCallback(`${server.url}/api/hand/tool`, "tok", PAYLOAD, controller.signal);
    await vi.waitFor(() => expect(server.requests()).toHaveLength(1));
    controller.abort();
    await expect(pending).resolves.toEqual({ kind: "abort" });
  });
});
