import type { Server } from "node:http";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { startFakeCallback, type FakeCallback } from "./fake-callback.js";
import { startFakeUpstream, type FakeScenario } from "./fake-upstream.js";
import { startServer } from "../src/main.js";

const TOKEN = "test-token";
const TINY_PNG =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

let server: Server;
let port = 0;

beforeAll(async () => {
  server = await startServer(0, TOKEN);
  const address = server.address();
  if (address === null || typeof address === "string") {
    throw new Error("hand server failed to bind");
  }
  port = address.port;
});

afterAll(async () => {
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

interface RunEvent {
  event: string;
  data: unknown;
}

function parseSse(text: string): RunEvent[] {
  return text
    .split("\n\n")
    .filter((block) => block.trim().length > 0)
    .flatMap((block) => {
      let event = "";
      let data: unknown;
      for (const line of block.split("\n")) {
        if (line.startsWith("event: ")) {
          event = line.slice("event: ".length);
        } else if (line.startsWith("data: ")) {
          const raw = line.slice("data: ".length);
          try {
            data = JSON.parse(raw);
          } catch {
            data = raw;
          }
        }
      }
      return event.length > 0 ? [{ event, data }] : [];
    });
}

function eventNames(events: RunEvent[]): string[] {
  return events.map((event) => event.event);
}

function modelSpec(upstreamPort: number, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    baseUrl: `http://127.0.0.1:${upstreamPort}/v1`,
    apiKey: "test-key",
    modelId: "cerebras/gpt-oss-120b",
    contextWindow: 131000,
    maxOutputTokens: 40000,
    reasoning: true,
    input: ["text"],
    ...overrides,
  };
}

function runRequest(upstreamPort: number, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    model: modelSpec(upstreamPort),
    messages: [{ role: "user", parts: [{ type: "text", text: "hi" }] }],
    runId: "run-test",
    chatId: "chat-test",
    ...extra,
  };
}

async function run(body: unknown): Promise<{ status: number; events: RunEvent[] }> {
  const response = await fetch(`http://127.0.0.1:${port}/v1/run`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  return { status: response.status, events: parseSse(text) };
}

const NORMAL: FakeScenario = [
  { chunk: { id: "chatcmpl-1", object: "chat.completion.chunk", model: "x", choices: [{ index: 0, delta: { reasoning: "thinking " } }] } },
  { chunk: { choices: [{ index: 0, delta: { reasoning: "hard" } }] } },
  { chunk: { choices: [{ index: 0, delta: { content: "Hel" } }] } },
  { chunk: { choices: [{ index: 0, delta: { content: "lo" } }] } },
  {
    chunk: {
      choices: [{ index: 0, delta: {}, finish_reason: "stop" }],
      usage: {
        prompt_tokens: 100,
        completion_tokens: 20,
        total_tokens: 120,
        prompt_tokens_details: { cached_tokens: 60, cache_write_tokens: 5 },
        completion_tokens_details: { reasoning_tokens: 7 },
      },
    },
  },
  { end: true },
];

const STOP: FakeScenario = [
  { chunk: { id: "c2", choices: [{ index: 0, delta: { content: "All done." } }] } },
  {
    chunk: {
      choices: [{ index: 0, delta: {}, finish_reason: "stop" }],
      usage: { prompt_tokens: 50, completion_tokens: 5, total_tokens: 55 },
    },
  },
  { end: true },
];

const TOOL_CALLS: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: "call_1", type: "function", function: { name: "search", arguments: "" } }] } }] } },
  { chunk: { choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { arguments: '{"quer' } }] } }] } },
  { chunk: { choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { arguments: 'y": "hel' } }, { index: 1, type: "function", function: { name: "fetch", arguments: "" } }] } }] } },
  { chunk: { choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { arguments: 'lo"}' } }, { index: 1, function: { arguments: '{"url":"x"}' } }] } }] } },
  {
    chunk: {
      choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }],
      usage: { prompt_tokens: 40, completion_tokens: 15, total_tokens: 55 },
    },
  },
  { end: true },
];

const TOOL_CALL_ONE: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: "call_img", type: "function", function: { name: "get_image", arguments: '{}' } }] } }] } },
  {
    chunk: {
      choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }],
      usage: { prompt_tokens: 30, completion_tokens: 10, total_tokens: 40 },
    },
  },
  { end: true },
];

const MIDSTREAM_ERROR: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { content: "par" } }] } },
  { error: { error: { message: "upstream exploded mid-stream", type: "server_error" } } },
  { end: true },
];

const TRUNCATED: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { content: "trail" } }] } },
  { end: true },
];

const EMPTY_STOP: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: {}, finish_reason: "stop" }] } },
  { end: true },
];

const LENGTH_NEAR: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { content: "partial answer" } }] } },
  {
    chunk: {
      choices: [{ index: 0, delta: {}, finish_reason: "length" }],
      usage: { prompt_tokens: 100000, completion_tokens: 300, total_tokens: 100300 },
    },
  },
  { end: true },
];

const LENGTH_FAR: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { content: "partial" } }] } },
  {
    chunk: {
      choices: [{ index: 0, delta: {}, finish_reason: "length" }],
      usage: { prompt_tokens: 1000, completion_tokens: 40000, total_tokens: 41000 },
    },
  },
  { end: true },
];

const LENGTH_NO_USAGE: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { content: "partial" } }] } },
  { chunk: { choices: [{ index: 0, delta: {}, finish_reason: "length" }] } },
  { end: true },
];

const SLOW: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { content: "first" } }] }, delay: 100 },
  { chunk: { choices: [{ index: 0, delta: { content: "second" } }] }, delay: 1000 },
  { chunk: { choices: [{ index: 0, delta: {}, finish_reason: "stop" }] }, delay: 1000 },
  { end: true },
];

const OVERFLOW_BODY = {
  message: "This model's maximum context length is 131072 tokens. However, you requested 200000 tokens.",
  type: "invalid_request_error",
};

const WEATHER_TOOLS = [
  { name: "search", description: "search", schema: { type: "object", properties: {} }, timeoutSeconds: 0 },
  { name: "fetch", description: "fetch", schema: { type: "object", properties: {} }, timeoutSeconds: 0 },
];

/** One tool that advertises a 1s execution budget (for the timeout tests). */
const SLOW_TOOL = { name: "get_image", description: "image", schema: { type: "object", properties: {} }, timeoutSeconds: 1 };

async function withCallback(
  upstream: Awaited<ReturnType<typeof startFakeUpstream>>,
  callback: FakeCallback,
  fn: (url: string) => Promise<void>,
): Promise<void> {
  try {
    await fn(callback.url);
  } finally {
    await upstream.close();
    await callback.close();
  }
}

describe("POST /v1/run", () => {
  it("relays deltas, reasoning, and the assembled message in order", async () => {
    const upstream = await startFakeUpstream(NORMAL);
    try {
      const { status, events } = await run(
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

  it("runs a tool round trip with split args and a synthesized id for the id-less call", async () => {
    const upstream = await startFakeUpstream([TOOL_CALLS, STOP]);
    const callback = await startFakeCallback();
    callback.scripted(
      { parts: [{ type: "text", text: "Berlin: 22C" }], isError: false },
      { parts: [{ type: "text", text: "fetched" }], isError: false },
    );
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { status, events } = await run(
        runRequest(upstream.port, { tools: WEATHER_TOOLS, toolCallbackUrl: callbackUrl }),
      );
      expect(status).toBe(200);
      expect(eventNames(events)).toEqual([
        "assistant_message",
        "tool_call",
        "tool_result",
        "tool_call",
        "tool_result",
        "text_delta",
        "assistant_message",
        "done",
      ]);

      const firstMessage = (events[0]?.data as { message: { parts: { type: string; id: string; tool: string; args: Record<string, unknown> }[] } }).message;
      expect(firstMessage.finishReason).toBe("tool_calls");
      const searchCall = firstMessage.parts.find((part) => part.tool === "search");
      const fetchCall = firstMessage.parts.find((part) => part.tool === "fetch");
      expect(searchCall?.args).toEqual({ query: "hello" });
      expect(fetchCall?.args).toEqual({ url: "x" });
      expect(fetchCall?.id).not.toBe("");
      expect(fetchCall?.id).not.toBe(searchCall?.id);

      expect(events[1]?.data).toEqual({ id: "call_1", name: "search", args: { query: "hello" } });
      expect(events[2]?.data).toEqual({
        id: "call_1",
        name: "search",
        parts: [{ type: "text", text: "Berlin: 22C" }],
        isError: false,
      });
      expect(events[3]?.data).toMatchObject({ name: "fetch", args: { url: "x" } });

      const requests = callback.requests();
      expect(requests).toHaveLength(2);
      expect(requests[0]).toMatchObject({
        runId: "run-test",
        chatId: "chat-test",
        id: "call_1",
        name: "search",
        args: { query: "hello" },
      });
      expect(requests[1]).toMatchObject({ name: "fetch", args: { url: "x" } });
      // the advertised execution budget is echoed back for the brain to enforce
      expect(requests.map((request) => request.timeoutSeconds)).toEqual([0, 0]);

      const secondBody = upstream.capturedAll()[1] as { messages: { role: string; tool_call_id?: string; content: unknown }[] };
      const toolMessages = secondBody.messages.filter((message) => message.role === "tool");
      expect(toolMessages).toHaveLength(2);
      expect(toolMessages[0]).toMatchObject({ tool_call_id: "call_1", content: "Berlin: 22C" });
    });
  });

  it("retries after a mid-stream error chunk", async () => {
    const upstream = await startFakeUpstream([MIDSTREAM_ERROR, NORMAL]);
    try {
      const { status, events } = await run(runRequest(upstream.port));
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
      const { events } = await run(runRequest(upstream.port));
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
      const { events } = await run(runRequest(upstream.port, { maxRetries: 1 }));
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
      const { events } = await run(runRequest(upstream.port));
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
      const { events } = await run(runRequest(upstream.port));
      expect(eventNames(events)).toEqual(["text_delta", "assistant_message", "error"]);
      const message = (events[1]?.data as { message: { finishReason: string; parts: { type: string; text: string }[] } }).message;
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
      const { events } = await run(runRequest(upstream.port));
      expect(eventNames(events).at(-1)).toBe("error");
      expect(events.at(-1)?.data).toMatchObject({ type: "output_budget_exhausted" });
    } finally {
      await upstream.close();
    }
  });

  it("a length finish without usage fails without assembling the partial", async () => {
    const upstream = await startFakeUpstream(LENGTH_NO_USAGE);
    try {
      const { events } = await run(runRequest(upstream.port));
      expect(eventNames(events)).toEqual(["text_delta", "error"]);
      expect(events[1]?.data).toMatchObject({ type: "output_budget_exhausted" });
    } finally {
      await upstream.close();
    }
  });

  it("classifies a gateway-side overflow rejection as context_exhausted", async () => {
    const upstream = await startFakeUpstream({ status: 400, body: OVERFLOW_BODY });
    try {
      const { events } = await run(runRequest(upstream.port));
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
      await run(runRequest(upstream.port, { messages: history }));
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
      await run(runRequest(upstream.port, { messages: history }));
      const captured = upstream.captured() as { messages: Record<string, unknown>[] };
      const assistant = captured.messages.find((message) => message.role === "assistant") as Record<string, unknown>;
      expect(assistant.reasoning_content).toBe("deep thoughts");
      expect(assistant.content).not.toContain("deep thoughts");
    } finally {
      await upstream.close();
    }
  });

  it("round-trips an image tool result into the next round", async () => {
    const upstream = await startFakeUpstream([TOOL_CALL_ONE, STOP]);
    const callback = await startFakeCallback();
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
        runRequest(upstream.port, {
          tools: [{ name: "get_image", description: "image", schema: { type: "object", properties: {} }, timeoutSeconds: 0 }],
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
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { events } = await run(
        runRequest(upstream.port, {
          tools: [{ name: "get_image", description: "image", schema: { type: "object", properties: {} }, timeoutSeconds: 0 }],
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
    callback.scripted({ fatal: { message: "tool exploded" } });
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { events } = await run(
        runRequest(upstream.port, {
          tools: [{ name: "get_image", description: "image", schema: { type: "object", properties: {} }, timeoutSeconds: 0 }],
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
    await withCallback(upstream, callback, async (callbackUrl) => {
      const { events } = await run(
        runRequest(upstream.port, {
          tools: [{ name: "get_image", description: "image", schema: { type: "object", properties: {} }, timeoutSeconds: 0 }],
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

  it(
    "waits a hanging callback out past the global callback timeout when the tool has its own budget",
    async () => {
      // the tool advertises a 1s budget, so the callback POST waits 1s + the
      // fixed 30s slack — far longer than the 50ms global callback timeout.
      // Waiting for the real abort would take 31s, so the test only proves
      // the global timeout did NOT fire early (which would end the run with
      // tool_transport within milliseconds).
      const upstream = await startFakeUpstream([TOOL_CALL_ONE, STOP]);
      const callback = await startFakeCallback();
      callback.scriptedHang();
      try {
        const controller = new AbortController();
        const response = await fetch(`http://127.0.0.1:${port}/v1/run`, {
          method: "POST",
          headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
          body: JSON.stringify(
            runRequest(upstream.port, {
              tools: [SLOW_TOOL],
              toolCallbackUrl: callback.url,
              callbackTimeoutMs: 50,
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
        // the advertised budget was echoed back to the brain
        expect(callback.requests()[0]?.timeoutSeconds).toBe(1);
        controller.abort();
      } finally {
        await upstream.close();
        await callback.close();
      }
    },
    10_000,
  );

  it(
    "aborts the upstream stream when the client disconnects",
    async () => {
      const upstream = await startFakeUpstream(SLOW);
      const closePromise = upstream.waitForClientClose();
      try {
        const controller = new AbortController();
        const response = await fetch(`http://127.0.0.1:${port}/v1/run`, {
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
    },
    20_000,
  );

  it("rejects a run with tools but no toolCallbackUrl", async () => {
    const upstream = await startFakeUpstream(NORMAL);
    try {
      const response = await fetch(`http://127.0.0.1:${port}/v1/run`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
        body: JSON.stringify(runRequest(upstream.port, { tools: WEATHER_TOOLS })),
      });
      expect(response.status).toBe(400);
      expect(await response.json()).toMatchObject({ ok: false, error: { type: "invalid_request" } });
      expect(upstream.connectionCount()).toBe(0);
    } finally {
      await upstream.close();
    }
  });

  it("rejects a tool without an explicit timeoutSeconds", async () => {
    const upstream = await startFakeUpstream(NORMAL);
    try {
      const response = await fetch(`http://127.0.0.1:${port}/v1/run`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
        body: JSON.stringify(
          runRequest(upstream.port, {
            tools: [{ name: "search", description: "search", schema: { type: "object", properties: {} } }],
            toolCallbackUrl: "http://127.0.0.1:9/api/hand/tool",
          }),
        ),
      });
      expect(response.status).toBe(400);
      expect(await response.json()).toMatchObject({
        ok: false,
        error: { type: "invalid_request", message: "tools[0].timeoutSeconds must be a non-negative integer" },
      });
      expect(upstream.connectionCount()).toBe(0);
    } finally {
      await upstream.close();
    }
  });
});
