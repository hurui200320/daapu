import { describe, expect, it } from "vitest";
import { startFakeUpstream } from "./fake-upstream.js";
import {
  EMPTY_STOP,
  LENGTH_FAR,
  LENGTH_NEAR,
  LENGTH_NO_USAGE,
  MIDSTREAM_ERROR,
  NORMAL,
  OVERFLOW_BODY,
  SLOW,
  TOKEN,
  TRUNCATED,
  eventNames,
  modelSpec,
  run,
  runRequest,
  withTestServer,
} from "./helpers.js";

const { port } = withTestServer();

describe("POST /v1/run streaming", () => {
  it("relays deltas, reasoning, and the assembled message in order", async () => {
    const upstream = await startFakeUpstream(NORMAL);
    try {
      const { status, events } = await run(
        port(),
        runRequest(upstream.port, { model: modelSpec(upstream.port, { reasoningEffort: "high" }) }),
      );
      expect(status).toBe(200);
      expect(eventNames(events)).toEqual([
        "reasoning_delta",
        "reasoning_delta",
        "text_delta",
        "text_delta",
        "assistant_message",
        "done",
      ]);
      expect(events[0]?.data).toEqual({ text: "thinking " });
      expect(events[1]?.data).toEqual({ text: "hard" });
      expect(events[2]?.data).toEqual({ text: "Hel" });
      expect(events[3]?.data).toEqual({ text: "lo" });
      const message = (events[4]?.data as { message: Record<string, unknown> }).message;
      expect(message.role).toBe("assistant");
      expect(message.parts).toEqual([
        { type: "reasoning", content: "thinking hard" },
        { type: "text", text: "Hello" },
      ]);
      expect(message.finishReason).toBe("stop");
      expect(message.meta).toMatchObject({
        inputTokens: 100,
        outputTokens: 20,
        totalTokens: 120,
        modelId: "cerebras/gpt-oss-120b",
      });
      expect(events[5]?.data).toEqual({ finishReason: "stop" });
      const captured = upstream.captured() as Record<string, unknown>;
      expect(captured.reasoning_effort).toBe("high");
      expect(captured.max_completion_tokens ?? captured.max_tokens).toBe(40000);
      expect(captured.tools).toBeUndefined();
    } finally {
      await upstream.close();
    }
  });

  it("retries after a mid-stream error chunk", async () => {
    const upstream = await startFakeUpstream([MIDSTREAM_ERROR, NORMAL]);
    try {
      const { status, events } = await run(port(), runRequest(upstream.port));
      expect(status).toBe(200);
      expect(eventNames(events).slice(0, 3)).toEqual(["text_delta", "retry", "reasoning_delta"]);
      expect(events[1]?.data).toEqual({
        attempt: 1,
        delayMs: 100,
        message: "upstream exploded mid-stream",
      });
      expect(eventNames(events).at(-1)).toBe("done");
      expect(upstream.connectionCount()).toBe(2);
    } finally {
      await upstream.close();
    }
  });

  it("retries after a truncated stream and succeeds", async () => {
    const upstream = await startFakeUpstream([TRUNCATED, NORMAL]);
    try {
      const { events } = await run(port(), runRequest(upstream.port));
      expect(eventNames(events).slice(0, 2)).toEqual(["text_delta", "retry"]);
      expect(eventNames(events).at(-1)).toBe("done");
      expect(upstream.connectionCount()).toBe(2);
    } finally {
      await upstream.close();
    }
  });

  it("honors maxRetries and fails the run when exhausted", async () => {
    const upstream = await startFakeUpstream(MIDSTREAM_ERROR);
    try {
      const { events } = await run(port(), runRequest(upstream.port, { maxRetries: 1 }));
      expect(eventNames(events)).toEqual(["text_delta", "error"]);
      expect(events[1]?.data).toMatchObject({
        type: "upstream",
        message: "maxRetries (1) exhausted: upstream exploded mid-stream",
      });
      expect(upstream.connectionCount()).toBe(1);
    } finally {
      await upstream.close();
    }
  });

  it("fails the run on a stop with neither text nor tool calls", async () => {
    const upstream = await startFakeUpstream([EMPTY_STOP]);
    try {
      const { events } = await run(port(), runRequest(upstream.port));
      expect(eventNames(events)).toEqual(["error"]);
      expect(events[0]?.data).toMatchObject({ type: "empty_response" });
      expect(upstream.connectionCount()).toBe(1);
    } finally {
      await upstream.close();
    }
  });

  it("classifies a near-window length finish as context_exhausted with the partial message preserved", async () => {
    const upstream = await startFakeUpstream(LENGTH_NEAR);
    try {
      const { events } = await run(port(), runRequest(upstream.port));
      expect(eventNames(events)).toEqual(["text_delta", "assistant_message", "error"]);
      const message = (
        events[1]?.data as { message: { finishReason: string; parts: { type: string; text: string }[] } }
      ).message;
      expect(message.finishReason).toBe("length");
      expect(message.parts).toEqual([{ type: "text", text: "partial answer" }]);
      expect(events[2]?.data).toMatchObject({ type: "context_exhausted" });
    } finally {
      await upstream.close();
    }
  });

  it("classifies a far-from-window length finish as output_budget_exhausted", async () => {
    const upstream = await startFakeUpstream(LENGTH_FAR);
    try {
      const { events } = await run(port(), runRequest(upstream.port));
      expect(eventNames(events).at(-1)).toBe("error");
      expect(events.at(-1)?.data).toMatchObject({ type: "output_budget_exhausted" });
    } finally {
      await upstream.close();
    }
  });

  it("a length finish without usage fails without assembling the partial", async () => {
    const upstream = await startFakeUpstream(LENGTH_NO_USAGE);
    try {
      const { events } = await run(port(), runRequest(upstream.port));
      expect(eventNames(events)).toEqual(["text_delta", "error"]);
      expect(events[1]?.data).toMatchObject({ type: "output_budget_exhausted" });
    } finally {
      await upstream.close();
    }
  });

  it("classifies a gateway-side overflow rejection as context_exhausted", async () => {
    const upstream = await startFakeUpstream({ status: 400, body: OVERFLOW_BODY });
    try {
      const { events } = await run(port(), runRequest(upstream.port));
      expect(eventNames(events)).toEqual(["error"]);
      expect(events[0]?.data).toMatchObject({ type: "context_exhausted" });
      expect(upstream.connectionCount()).toBe(1);
    } finally {
      await upstream.close();
    }
  });

  it("omits historical reasoning from the wire for a non-reasoning model", async () => {
    const upstream = await startFakeUpstream(NORMAL);
    try {
      const history = [
        {
          role: "assistant",
          parts: [
            { type: "reasoning", content: "deep thoughts" },
            { type: "text", text: "previous answer" },
          ],
          meta: { inputTokens: 10, outputTokens: 4, totalTokens: 14, modelId: "cerebras/gpt-oss-120b" },
          finishReason: "stop",
        },
      ];
      const { events } = await run(
        port(),
        runRequest(upstream.port, { messages: history, model: modelSpec(upstream.port, { reasoning: false }) }),
      );
      expect(eventNames(events).at(-1)).toBe("done");
      const captured = upstream.captured() as { reasoning_effort?: string; messages: Record<string, unknown>[] };
      expect(captured.reasoning_effort).toBeUndefined();
      const assistant = captured.messages.find((message) => message.role === "assistant") as Record<string, unknown>;
      expect(assistant.reasoning_content).toBeUndefined();
      expect(JSON.stringify(captured.messages)).not.toContain("deep thoughts");
    } finally {
      await upstream.close();
    }
  });

  it("replays historical reasoning on the wire for the same reasoning model", async () => {
    const upstream = await startFakeUpstream(NORMAL);
    try {
      const history = [
        {
          role: "assistant",
          parts: [
            { type: "reasoning", content: "deep thoughts" },
            { type: "text", text: "previous answer" },
          ],
          meta: { inputTokens: 10, outputTokens: 4, totalTokens: 14, modelId: "cerebras/gpt-oss-120b" },
          finishReason: "stop",
        },
      ];
      await run(port(), runRequest(upstream.port, { messages: history }));
      const captured = upstream.captured() as { messages: Record<string, unknown>[] };
      const assistant = captured.messages.find((message) => message.role === "assistant") as Record<string, unknown>;
      expect(assistant.reasoning_content).toBe("deep thoughts");
    } finally {
      await upstream.close();
    }
  });

  it("replays historical reasoning as reasoning for a different reasoning model", async () => {
    const upstream = await startFakeUpstream(NORMAL);
    try {
      const history = [
        {
          role: "assistant",
          parts: [
            { type: "reasoning", content: "deep thoughts" },
            { type: "text", text: "previous answer" },
          ],
          // a different producing model than the current run's model
          meta: { inputTokens: 10, outputTokens: 4, totalTokens: 14, modelId: "some/other-model" },
          finishReason: "stop",
        },
      ];
      await run(port(), runRequest(upstream.port, { messages: history }));
      const captured = upstream.captured() as { messages: Record<string, unknown>[] };
      const assistant = captured.messages.find((message) => message.role === "assistant") as Record<string, unknown>;
      expect(assistant.reasoning_content).toBe("deep thoughts");
      expect(assistant.content).not.toContain("deep thoughts");
    } finally {
      await upstream.close();
    }
  });

  it("aborts the upstream stream when the client disconnects", async () => {
    const upstream = await startFakeUpstream(SLOW);
    const closePromise = upstream.waitForClientClose();
    try {
      const controller = new AbortController();
      const response = await fetch(`http://127.0.0.1:${port()}/v1/run`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
        body: JSON.stringify(runRequest(upstream.port)),
        signal: controller.signal,
      });
      expect(response.status).toBe(200);
      const reader = response.body?.getReader();
      expect(reader).toBeDefined();
      const decoder = new TextDecoder();
      let buffer = "";
      while (reader) {
        const { done, value } = await reader.read();
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        if (buffer.includes("event: text_delta")) {
          controller.abort();
          break;
        }
      }
      await closePromise;
    } finally {
      await upstream.close();
    }
  }, 20_000);
});
