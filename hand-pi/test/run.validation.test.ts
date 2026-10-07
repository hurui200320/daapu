import { connect } from "node:net";
import { describe, expect, it } from "vitest";
import { startFakeUpstream } from "./fake-upstream.js";
import { captureConsoleError, NORMAL, runRequest, TOKEN, withTestServer } from "./helpers.js";
import { MAX_BODY_BYTES } from "../src/http.js";
import { SERVICE_VERSION } from "../src/routes.js";

const { port } = withTestServer();
// the hand's failure-path logging is silenced (and captured) file-wide —
// see [captureConsoleError]; the rejection test below asserts on the
// captured lines
const consoleError = captureConsoleError();

describe("/v1/health", () => {
  it("answers with the service version", async () => {
    const response = await fetch(`http://127.0.0.1:${port()}/v1/health`, {
      headers: { "x-daapu-token": TOKEN },
    });
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true, version: SERVICE_VERSION });
  });

  it("answers without a token (the docker/k8s probe contract)", async () => {
    const response = await fetch(`http://127.0.0.1:${port()}/v1/health`);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true, version: SERVICE_VERSION });
  });

  it("keeps the run route behind the token", async () => {
    const response = await fetch(`http://127.0.0.1:${port()}/v1/run`, { method: "POST" });
    expect(response.status).toBe(401);
    expect(await response.json()).toMatchObject({ ok: false, error: { type: "auth" } });
    // observability contract: the token rejection logs one `request rejected`
    // line like every rejection (see handleRequest in src/main.ts)
    const rejectedLines = consoleError.lines().filter((line) => line.includes("request rejected"));
    expect(rejectedLines).toEqual(["[hand] request rejected error=auth: invalid or missing x-daapu-token"]);
  });

  it("answers 404 for an unknown route and logs the rejection", async () => {
    const response = await fetch(`http://127.0.0.1:${port()}/v1/nope`, {
      headers: { "x-daapu-token": TOKEN },
    });
    expect(response.status).toBe(404);
    expect(await response.json()).toMatchObject({ ok: false, error: { type: "invalid_request" } });
    // observability contract: same mirror as the token rejection above
    const rejectedLines = consoleError.lines().filter((line) => line.includes("request rejected"));
    expect(rejectedLines).toEqual(["[hand] request rejected error=invalid_request: no route for GET /v1/nope"]);
  });
});

describe("POST /v1/run request envelope", () => {
  it("rejects a run missing a run-policy knob", async () => {
    const upstream = await startFakeUpstream(NORMAL);
    try {
      for (const omitted of ["maxTokens", "maxRounds", "maxRetries", "streamIdleTimeoutMs"]) {
        const body = runRequest(upstream.port);
        delete body[omitted];
        const response = await fetch(`http://127.0.0.1:${port()}/v1/run`, {
          method: "POST",
          headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
          body: JSON.stringify(body),
        });
        expect(response.status).toBe(400);
        expect(await response.json()).toMatchObject({ ok: false, error: { type: "invalid_request" } });
      }
      expect(upstream.connectionCount()).toBe(0);
      // observability contract: an envelope the validator rejects logs one
      // `request rejected` line per attempt with the reason (the contract:
      // see [respondFailure] in src/http.ts)
      const rejectedLines = consoleError.lines().filter((line) => line.includes("request rejected"));
      expect(rejectedLines).toEqual([
        "[hand] request rejected error=invalid_request: maxTokens must be a positive integer",
        "[hand] request rejected error=invalid_request: maxRounds must be a non-negative integer",
        "[hand] request rejected error=invalid_request: maxRetries must be a non-negative integer",
        "[hand] request rejected error=invalid_request: streamIdleTimeoutMs must be a non-negative integer",
      ]);
    } finally {
      await upstream.close();
    }
  });

  it("rejects a run with an empty messages array", async () => {
    const upstream = await startFakeUpstream(NORMAL);
    try {
      const response = await fetch(`http://127.0.0.1:${port()}/v1/run`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
        body: JSON.stringify(runRequest(upstream.port, { messages: [] })),
      });
      expect(response.status).toBe(400);
      expect(await response.json()).toMatchObject({ ok: false, error: { type: "invalid_request" } });
      expect(upstream.connectionCount()).toBe(0);
    } finally {
      await upstream.close();
    }
  });

  it("rejects a run with toolListUrl but no toolCallbackUrl", async () => {
    const upstream = await startFakeUpstream(NORMAL);
    try {
      const response = await fetch(`http://127.0.0.1:${port()}/v1/run`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
        body: JSON.stringify(runRequest(upstream.port, { toolListUrl: "http://127.0.0.1:9/tools" })),
      });
      expect(response.status).toBe(400);
      expect(await response.json()).toMatchObject({ ok: false, error: { type: "invalid_request" } });
      expect(upstream.connectionCount()).toBe(0);
    } finally {
      await upstream.close();
    }
  });

  it("rejects a run with a non-http(s) toolListUrl", async () => {
    const upstream = await startFakeUpstream(NORMAL);
    try {
      const response = await fetch(`http://127.0.0.1:${port()}/v1/run`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
        body: JSON.stringify(
          runRequest(upstream.port, {
            toolListUrl: "ftp://127.0.0.1/tools",
            toolCallbackUrl: "http://127.0.0.1:9/api/hand/tool",
          }),
        ),
      });
      expect(response.status).toBe(400);
      expect(await response.json()).toMatchObject({
        ok: false,
        error: { type: "invalid_request", message: "toolListUrl must be an http(s) URL" },
      });
      expect(upstream.connectionCount()).toBe(0);
    } finally {
      await upstream.close();
    }
  });
});

describe("request body cap", () => {
  it("rejects an oversized declared body and logs the rejection", async () => {
    // fetch cannot lie about content-length, so a raw socket carries the
    // oversized declaration; readBody rejects it before reading anything
    // and the rejection funnels to [respondFailure] in src/http.ts
    let responseText = "";
    await new Promise<void>((resolve, reject) => {
      const socket = connect(port(), "127.0.0.1");
      socket.on("error", reject);
      socket.on("data", (chunk: Buffer) => {
        responseText += chunk.toString();
        if (responseText.includes("\r\n\r\n")) {
          socket.destroy();
          resolve();
        }
      });
      socket.end(
        `POST /v1/run HTTP/1.1\r\nhost: hand\r\nx-daapu-token: ${TOKEN}\r\n` +
          `content-type: application/json\r\ncontent-length: ${MAX_BODY_BYTES + 1}\r\n\r\n`,
      );
    });
    expect(responseText).toContain("HTTP/1.1 400");
    // observability contract: like every HandFailure rejection, the body-cap
    // rejection logs one `request rejected` line with the reason
    const rejectedLines = consoleError.lines().filter((line) => line.includes("request rejected"));
    expect(rejectedLines).toEqual([
      `[hand] request rejected error=invalid_request: request body exceeds the ${MAX_BODY_BYTES} byte cap`,
    ]);
  });
});
