import { describe, expect, it } from "vitest";
import { startFakeCallback } from "./fake-callback.js";
import { startFakeUpstream } from "./fake-upstream.js";
import {
  IMAGE_TOOL,
  MIDSTREAM_ERROR,
  NORMAL,
  SLOW_TOOL,
  TINY_PNG,
  TOKEN,
  TOOL_CALLS,
  TOOL_CALL_ONE,
  STOP,
  WEATHER_TOOLS,
  eventNames,
  modelSpec,
  run,
  runRequest,
  withCallback,
  withTestServer,
} from "./helpers.js";

const { port } = withTestServer();

describe("POST /v1/run tool rounds", () => {
  it("runs a tool round trip with split args and a synthesized id for the id-less call", async () => {
    const upstream = await startFakeUpstream([TOOL_CALLS, STOP]);
    const callback = await startFakeCallback();
    callback.scriptedToolList({ tools: WEATHER_TOOLS });
    // responses are keyed by call identity: parallel arrival order is not
    // deterministic, so the queue-based `scripted` pairing would be racy.
    // The gate also proves concurrency: neither callback answers until BOTH
    // requests have arrived, so a serial hand would deadlock the run.
    callback.scriptedGate(2, (request) =>
      request.name === "search"
        ? { parts: [{ type: "text", text: "Berlin: 22C" }], isError: false }
        : { parts: [{ type: "text", text: "fetched" }], isError: false },
    );
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { status, events } = await run(
        port(),
        runRequest(upstream.port, { toolListUrl: callback.toolsUrl, toolCallbackUrl: callbackUrl }),
      );
      expect(status).toBe(200);
      // the round's tool calls are announced up front (all `tool_call`
      // events precede the `tool_result` events, which arrive in call order)
      expect(eventNames(events)).toEqual([
        "assistant_message",
        "tool_call",
        "tool_call",
        "tool_result",
        "tool_result",
        "text_delta",
        "assistant_message",
        "done",
      ]);

      const firstMessage = (
        events[0]?.data as {
          message: {
            finishReason: string;
            parts: { type: string; id: string; tool: string; args: Record<string, unknown> }[];
          };
        }
      ).message;
      expect(firstMessage.finishReason).toBe("tool_calls");
      const searchCall = firstMessage.parts.find((part) => part.tool === "search");
      const fetchCall = firstMessage.parts.find((part) => part.tool === "fetch");
      expect(searchCall?.args).toEqual({ query: "hello" });
      expect(fetchCall?.args).toEqual({ url: "x" });
      expect(fetchCall?.id).not.toBe("");
      expect(fetchCall?.id).not.toBe(searchCall?.id);

      expect(events[1]?.data).toEqual({ id: "call_1", name: "search", args: { query: "hello" } });
      // the fetch call is id-less in the stream, so the hand synthesizes one
      expect(events[2]?.data).toMatchObject({ name: "fetch", args: { url: "x" } });
      // tool_result events are reassembled in source order, not completion order
      expect(events[3]?.data).toEqual({
        id: "call_1",
        name: "search",
        parts: [{ type: "text", text: "Berlin: 22C" }],
        isError: false,
      });
      expect(events[4]?.data).toMatchObject({
        name: "fetch",
        parts: [{ type: "text", text: "fetched" }],
        isError: false,
      });

      // the callbacks fire concurrently: both requests arrived (in any order)
      const requests = callback.requests();
      expect(requests).toHaveLength(2);
      const searchRequest = requests.find((request) => request.name === "search");
      const fetchRequest = requests.find((request) => request.name === "fetch");
      expect(searchRequest).toMatchObject({
        runId: "run-test",
        id: "call_1",
        args: { query: "hello" },
      });
      expect(fetchRequest).toMatchObject({ name: "fetch", args: { url: "x" } });

      // the tools were re-fetched before every LLM request (two rounds)
      expect(callback.toolListRequests()).toEqual(["run-test", "run-test"]);

      const secondBody = upstream.capturedAll()[1] as {
        messages: { role: string; tool_call_id?: string; content: unknown }[];
      };
      const toolMessages = secondBody.messages.filter((message) => message.role === "tool");
      expect(toolMessages).toHaveLength(2);
      // history keeps the results in the same order as their calls
      expect(toolMessages[0]).toMatchObject({ tool_call_id: "call_1", content: "Berlin: 22C" });
      expect(toolMessages[1]).toMatchObject({ content: "fetched" });
    });
  });

  it("a fatal callback in a parallel batch fails the run and drops every result", async () => {
    // one call fails fatally while its sibling succeeds: the whole round
    // fails with tool_transport and NO result is assembled into history or
    // the SSE stream, even though the successful tool DID execute server-side
    // — its result is discarded because the round is thrown away.
    const upstream = await startFakeUpstream([TOOL_CALLS, STOP]);
    const callback = await startFakeCallback();
    callback.scriptedToolList({ tools: WEATHER_TOOLS });
    callback.scriptedGate(2, (request) =>
      request.name === "search"
        ? { fatal: { message: "search exploded" } }
        : { parts: [{ type: "text", text: "fetched" }], isError: false },
    );
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { status, events } = await run(
        port(),
        runRequest(upstream.port, { toolListUrl: callback.toolsUrl, toolCallbackUrl: callbackUrl }),
      );
      expect(status).toBe(200);
      // both calls were announced, no result was assembled, the run failed
      expect(eventNames(events)).toEqual(["assistant_message", "tool_call", "tool_call", "error"]);
      expect(events[3]?.data).toEqual({ type: "tool_transport", message: "search exploded" });
      // both tools DID execute (the fetch result is discarded, not stored)
      expect(callback.requests()).toHaveLength(2);
      // the failure ended the run: no second LLM round
      expect(upstream.connectionCount()).toBe(1);
    });
  });

  it("round-trips an image tool result into the next round", async () => {
    const upstream = await startFakeUpstream([TOOL_CALL_ONE, STOP]);
    const callback = await startFakeCallback();
    callback.scriptedToolList({ tools: IMAGE_TOOL });
    callback.scripted({
      parts: [
        {
          type: "attachment",
          kind: "image",
          content: { type: "base64", base64: TINY_PNG },
          mimeType: "image/png",
        },
      ],
      isError: false,
    });
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { events } = await run(
        port(),
        runRequest(upstream.port, {
          toolListUrl: callback.toolsUrl,
          toolCallbackUrl: callbackUrl,
          model: modelSpec(upstream.port, { input: ["text", "image"] }),
        }),
      );
      expect(eventNames(events).at(-1)).toBe("done");
      const toolResult = events.find((event) => event.event === "tool_result");
      expect(toolResult?.data).toEqual({
        id: "call_img",
        name: "get_image",
        parts: [
          {
            type: "attachment",
            kind: "image",
            content: { type: "base64", base64: TINY_PNG },
            mimeType: "image/png",
          },
        ],
        isError: false,
      });
      const secondBody = upstream.capturedAll()[1] as { messages: { role: string; content: unknown }[] };
      const user = secondBody.messages.filter((message) => message.role === "user").at(-1) as {
        content: { type: string; text?: string; image_url?: { url: string } }[];
      };
      const imageBlock = user.content.find((block) => block.type === "image_url");
      expect(imageBlock?.image_url?.url).toBe(`data:image/png;base64,${TINY_PNG}`);
      expect(user.content.some((block) => block.text === "Attached image(s) from tool result:")).toBe(true);
    });
  });

  it("ends the run with round_limit without executing tools past the cap", async () => {
    const upstream = await startFakeUpstream([TOOL_CALL_ONE, STOP]);
    const callback = await startFakeCallback();
    callback.scriptedToolList({ tools: IMAGE_TOOL });
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { events } = await run(
        port(),
        runRequest(upstream.port, {
          toolListUrl: callback.toolsUrl,
          toolCallbackUrl: callbackUrl,
          maxRounds: 1,
        }),
      );
      expect(eventNames(events)).toEqual(["assistant_message", "error"]);
      expect(events[1]?.data).toMatchObject({ type: "round_limit" });
      expect(callback.requests()).toHaveLength(0);
    });
  });

  it("fails the run with tool_transport on a fatal callback response", async () => {
    const upstream = await startFakeUpstream([TOOL_CALL_ONE, STOP]);
    const callback = await startFakeCallback();
    callback.scriptedToolList({ tools: IMAGE_TOOL });
    callback.scripted({ fatal: { message: "tool exploded" } });
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { events } = await run(
        port(),
        runRequest(upstream.port, {
          toolListUrl: callback.toolsUrl,
          toolCallbackUrl: callbackUrl,
        }),
      );
      expect(eventNames(events)).toEqual(["assistant_message", "tool_call", "error"]);
      expect(events[2]?.data).toEqual({ type: "tool_transport", message: "tool exploded" });
    });
  });

  it("fails the run with tool_transport on a non-200 callback response", async () => {
    const upstream = await startFakeUpstream([TOOL_CALL_ONE, STOP]);
    const callback = await startFakeCallback();
    callback.scriptedToolList({ tools: IMAGE_TOOL });
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { events } = await run(
        port(),
        runRequest(upstream.port, {
          toolListUrl: callback.toolsUrl,
          toolCallbackUrl: callbackUrl,
        }),
      );
      expect(eventNames(events)).toEqual(["assistant_message", "tool_call", "error"]);
      expect(events[2]?.data).toEqual({
        type: "tool_transport",
        message: "tool callback returned HTTP 500",
      });
    });
  });

  it("waits for a slow callback answer without applying its own deadline", async () => {
    // the hand applies no deadline of its own: the brain enforces the
    // execution budgets and always answers. A 2s-delayed callback answer
    // must be accepted and the run must continue to done.
    const upstream = await startFakeUpstream([TOOL_CALL_ONE, STOP]);
    const callback = await startFakeCallback();
    callback.scriptedToolList({ tools: [SLOW_TOOL] });
    callback.scriptedDelayed(2_000, {
      parts: [{ type: "text", text: "late answer" }],
      isError: false,
    });
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { events } = await run(
        port(),
        runRequest(upstream.port, {
          toolListUrl: callback.toolsUrl,
          toolCallbackUrl: callbackUrl,
        }),
      );
      expect(eventNames(events).at(-1)).toBe("done");
      const toolResult = events.find((event) => event.event === "tool_result");
      expect(toolResult?.data).toMatchObject({
        name: "get_image",
        parts: [{ type: "text", text: "late answer" }],
      });
    });
  }, 10_000);

  it("does not time out a hanging callback", async () => {
    // the callback POST has no hand-side deadline (the brain enforces the
    // budgets itself): a hanging callback must not fail the run early.
    // Waiting for the real abort would take forever, so the test only
    // proves no error arrived within the observation window, then aborts
    // via the client.
    const upstream = await startFakeUpstream([TOOL_CALL_ONE, STOP]);
    const callback = await startFakeCallback();
    callback.scriptedToolList({ tools: [SLOW_TOOL] });
    callback.scriptedHang();
    try {
      const controller = new AbortController();
      const response = await fetch(`http://127.0.0.1:${port()}/v1/run`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
        body: JSON.stringify(
          runRequest(upstream.port, {
            toolListUrl: callback.toolsUrl,
            toolCallbackUrl: callback.url,
          }),
        ),
        signal: controller.signal,
      });
      expect(response.status).toBe(200);
      const reader = response.body?.getReader();
      expect(reader).toBeDefined();
      const decoder = new TextDecoder();
      let buffer = "";
      let streamEnded = false;
      let sawError = false;
      const deadline = Date.now() + 1_000;
      while (reader && Date.now() < deadline) {
        const chunk = await Promise.race([
          reader.read().then((result) => ({ kind: "read" as const, ...result })),
          new Promise<{ kind: "wait" }>((resolve) => setTimeout(() => resolve({ kind: "wait" }), 50)),
        ]);
        if (chunk.kind === "read") {
          if (chunk.done) {
            streamEnded = true;
            break;
          }
          buffer += decoder.decode(chunk.value, { stream: true });
          if (buffer.includes("event: error")) {
            sawError = true;
            break;
          }
        }
      }
      expect(buffer).toContain("event: tool_call");
      expect(sawError).toBe(false);
      expect(streamEnded).toBe(false);
      controller.abort();
    } finally {
      await upstream.close();
      await callback.close();
    }
  }, 10_000);

  it("aborts silently when the client disconnects during the tool-list fetch", async () => {
    // a listing that never answers is NOT a tool_transport failure: the
    // hand aborts on the disconnect and the run closes without a terminal
    // event (the same silent exit as a mid-stream client disconnect)
    const upstream = await startFakeUpstream([TOOL_CALL_ONE, STOP]);
    const callback = await startFakeCallback();
    callback.scriptedToolListHang();
    try {
      const controller = new AbortController();
      const response = await fetch(`http://127.0.0.1:${port()}/v1/run`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
        body: JSON.stringify(
          runRequest(upstream.port, {
            toolListUrl: callback.toolsUrl,
            toolCallbackUrl: callback.url,
          }),
        ),
        signal: controller.signal,
      });
      expect(response.status).toBe(200);
      const reader = response.body?.getReader();
      expect(reader).toBeDefined();
      // deterministic: abort only once the GET is in flight (and hanging)
      await callback.waitForToolListRequests(1);
      controller.abort();
      const decoder = new TextDecoder();
      let buffer = "";
      try {
        while (reader) {
          const { done, value } = await reader.read();
          if (done) {
            break;
          }
          buffer += decoder.decode(value, { stream: true });
        }
      } catch {
        // the client-side abort may surface as a read error; either way
        // the run had no chance to emit anything after the disconnect
      }
      expect(buffer).not.toContain("event: error");
      expect(buffer).not.toContain("event: done");
      // the listing never answered, so the LLM request never happened
      expect(upstream.connectionCount()).toBe(0);
    } finally {
      await upstream.close();
      await callback.close();
    }
  }, 10_000);

  it("fetches the tool list before every LLM request and picks up a changed set between rounds", async () => {
    const upstream = await startFakeUpstream([TOOL_CALL_ONE, TOOL_CALL_ONE, STOP]);
    const callback = await startFakeCallback();
    callback.scriptedToolList(
      { tools: IMAGE_TOOL },
      {
        tools: [
          { name: "get_image", description: "image", schema: { type: "object", properties: {} } },
          { name: "search", description: "search", schema: { type: "object", properties: {} } },
        ],
      },
      {
        tools: [
          { name: "get_image", description: "image", schema: { type: "object", properties: {} } },
          { name: "search", description: "search", schema: { type: "object", properties: {} } },
        ],
      },
    );
    callback.scripted(
      { parts: [{ type: "text", text: "img" }], isError: false },
      { parts: [{ type: "text", text: "img" }], isError: false },
    );
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { events } = await run(
        port(),
        runRequest(upstream.port, {
          toolListUrl: callback.toolsUrl,
          toolCallbackUrl: callbackUrl,
        }),
      );
      expect(eventNames(events).at(-1)).toBe("done");
      // three rounds -> three per-LLM-request fetches, always with the runId
      expect(callback.toolListRequests()).toEqual(["run-test", "run-test", "run-test"]);
      // each round's LLM request was built with that round's fetched list
      const captured = upstream.capturedAll() as { tools?: { function: { name: string } }[] }[];
      expect(captured[0]?.tools?.map((tool) => tool.function.name)).toEqual(["get_image"]);
      expect(captured[1]?.tools?.map((tool) => tool.function.name)).toEqual(["get_image", "search"]);
      expect(captured[2]?.tools?.map((tool) => tool.function.name)).toEqual(["get_image", "search"]);
    });
  });

  it("re-fetches the tool list when a round retries after a transient error", async () => {
    const upstream = await startFakeUpstream([MIDSTREAM_ERROR, NORMAL]);
    const callback = await startFakeCallback();
    // one fetch per attempt: the failed attempt's round, then the retried one
    callback.scriptedToolList(
      { tools: IMAGE_TOOL },
      {
        tools: [
          { name: "get_image", description: "image", schema: { type: "object", properties: {} } },
          { name: "search", description: "search", schema: { type: "object", properties: {} } },
        ],
      },
    );
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { status, events } = await run(
        port(),
        runRequest(upstream.port, {
          toolListUrl: callback.toolsUrl,
          toolCallbackUrl: callbackUrl,
        }),
      );
      expect(status).toBe(200);
      expect(eventNames(events).slice(0, 3)).toEqual(["text_delta", "retry", "reasoning_delta"]);
      expect(eventNames(events).at(-1)).toBe("done");
      // fetched per attempt (two attempts of one round), always with the runId
      expect(callback.toolListRequests()).toEqual(["run-test", "run-test"]);
      // the retried attempt was built with the freshly fetched list
      const captured = upstream.capturedAll() as { tools?: { function: { name: string } }[] }[];
      expect(captured[0]?.tools?.map((tool) => tool.function.name)).toEqual(["get_image"]);
      expect(captured[1]?.tools?.map((tool) => tool.function.name)).toEqual(["get_image", "search"]);
    });
  });

  it("fails the run with tool_transport when the tool listing fails", async () => {
    const upstream = await startFakeUpstream(NORMAL);
    const callback = await startFakeCallback();
    callback.scriptedToolList({ status: 500 });
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { events } = await run(
        port(),
        runRequest(upstream.port, {
          toolListUrl: callback.toolsUrl,
          toolCallbackUrl: callbackUrl,
        }),
      );
      expect(eventNames(events)).toEqual(["error"]);
      expect(events[0]?.data).toEqual({
        type: "tool_transport",
        message: "tool listing returned HTTP 500",
      });
      expect(upstream.connectionCount()).toBe(0);
    });
  });

  it("fails the run with tool_transport on a malformed tool list", async () => {
    const upstream = await startFakeUpstream(NORMAL);
    const callback = await startFakeCallback();
    // a tool without the required schema must be rejected
    callback.scriptedToolList({
      tools: [{ name: "search", description: "search" }],
    });
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { events } = await run(
        port(),
        runRequest(upstream.port, {
          toolListUrl: callback.toolsUrl,
          toolCallbackUrl: callbackUrl,
        }),
      );
      expect(eventNames(events)).toEqual(["error"]);
      expect(events[0]?.data).toMatchObject({
        type: "tool_transport",
        message: expect.stringContaining("tool listing returned invalid tools"),
      });
      expect(upstream.connectionCount()).toBe(0);
    });
  });
});
