/**
 * Shared infrastructure and wire fixtures for the `/v1/run` test files:
 * server bootstrap, SSE parsing, request builders, and scripted upstream
 * scenarios. The scenarios are raw OpenAI-compatible chunk scripts — see
 * `fake-upstream.ts` for the runner.
 */

import type { Server } from "node:http";
import { afterAll, beforeAll } from "vitest";
import type { FakeCallback } from "./fake-callback.js";
import { startFakeUpstream, type FakeScenario } from "./fake-upstream.js";
import { startServer } from "../src/main.js";

export const TOKEN = "test-token";

export const TINY_PNG =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

export interface RunEvent {
  event: string;
  data: unknown;
}

/** Boots the hand on an ephemeral port for one test file; pair with `teardownServer`. */
export async function bootServer(): Promise<{ server: Server; port: number }> {
  const server = await startServer(0, TOKEN);
  const address = server.address();
  if (address === null || typeof address === "string") {
    throw new Error("hand server failed to bind");
  }
  return { server, port: address.port };
}

/** The `afterAll` half of `bootServer`. */
export function teardownServer(server: Server): Promise<void> {
  return new Promise<void>((resolve) => server.close(() => resolve()));
}

/** Registers the server lifecycle hooks for a test file; returns the live port. */
export function withTestServer(): { port: () => number } {
  let server: Server;
  let port = 0;
  beforeAll(async () => {
    ({ server, port } = await bootServer());
  });
  afterAll(() => teardownServer(server));
  return { port: () => port };
}

export function parseSse(text: string): RunEvent[] {
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

export function eventNames(events: RunEvent[]): string[] {
  return events.map((event) => event.event);
}

export function modelSpec(upstreamPort: number, overrides: Record<string, unknown> = {}): Record<string, unknown> {
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

export function runRequest(upstreamPort: number, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    model: modelSpec(upstreamPort),
    messages: [{ role: "user", parts: [{ type: "text", text: "hi" }] }],
    runId: "run-test",
    // the hand holds no defaults: every parameter is required per request
    maxTokens: 40000,
    maxRounds: 64,
    maxRetries: 0,
    streamIdleTimeoutMs: 300000,
    ...extra,
  };
}

export async function run(
  port: number,
  body: unknown,
  signal?: AbortSignal,
): Promise<{ status: number; events: RunEvent[] }> {
  const response = await fetch(`http://127.0.0.1:${port}/v1/run`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
    body: JSON.stringify(body),
    signal,
  });
  const text = await response.text();
  return { status: response.status, events: parseSse(text) };
}

export async function withCallback(
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

// ---------------------------------------------------------------------------
// Scripted upstream scenarios (OpenAI-compatible chunk scripts)
// ---------------------------------------------------------------------------

export const NORMAL: FakeScenario = [
  {
    chunk: {
      id: "chatcmpl-1",
      object: "chat.completion.chunk",
      model: "x",
      choices: [{ index: 0, delta: { reasoning: "thinking " } }],
    },
  },
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

export const STOP: FakeScenario = [
  { chunk: { id: "c2", choices: [{ index: 0, delta: { content: "All done." } }] } },
  {
    chunk: {
      choices: [{ index: 0, delta: {}, finish_reason: "stop" }],
      usage: { prompt_tokens: 50, completion_tokens: 5, total_tokens: 55 },
    },
  },
  { end: true },
];

export const TOOL_CALLS: FakeScenario = [
  {
    chunk: {
      id: "c1",
      choices: [
        {
          index: 0,
          delta: {
            tool_calls: [{ index: 0, id: "call_1", type: "function", function: { name: "search", arguments: "" } }],
          },
        },
      ],
    },
  },
  { chunk: { choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { arguments: '{"quer' } }] } }] } },
  {
    chunk: {
      choices: [
        {
          index: 0,
          delta: {
            tool_calls: [
              { index: 0, function: { arguments: 'y": "hel' } },
              { index: 1, type: "function", function: { name: "fetch", arguments: "" } },
            ],
          },
        },
      ],
    },
  },
  {
    chunk: {
      choices: [
        {
          index: 0,
          delta: {
            tool_calls: [
              { index: 0, function: { arguments: 'lo"}' } },
              { index: 1, function: { arguments: '{"url":"x"}' } },
            ],
          },
        },
      ],
    },
  },
  {
    chunk: {
      choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }],
      usage: { prompt_tokens: 40, completion_tokens: 15, total_tokens: 55 },
    },
  },
  { end: true },
];

export const TOOL_CALL_ONE: FakeScenario = [
  {
    chunk: {
      id: "c1",
      choices: [
        {
          index: 0,
          delta: {
            tool_calls: [
              { index: 0, id: "call_img", type: "function", function: { name: "get_image", arguments: "{}" } },
            ],
          },
        },
      ],
    },
  },
  {
    chunk: {
      choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }],
      usage: { prompt_tokens: 30, completion_tokens: 10, total_tokens: 40 },
    },
  },
  { end: true },
];

export const MIDSTREAM_ERROR: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { content: "par" } }] } },
  { error: { error: { message: "upstream exploded mid-stream", type: "server_error" } } },
  { end: true },
];

export const TRUNCATED: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { content: "trail" } }] } },
  { end: true },
];

export const EMPTY_STOP: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: {}, finish_reason: "stop" }] } },
  { end: true },
];

export const LENGTH_NEAR: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { content: "partial answer" } }] } },
  {
    chunk: {
      choices: [{ index: 0, delta: {}, finish_reason: "length" }],
      usage: { prompt_tokens: 100000, completion_tokens: 300, total_tokens: 100300 },
    },
  },
  { end: true },
];

export const LENGTH_FAR: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { content: "partial" } }] } },
  {
    chunk: {
      choices: [{ index: 0, delta: {}, finish_reason: "length" }],
      usage: { prompt_tokens: 1000, completion_tokens: 40000, total_tokens: 41000 },
    },
  },
  { end: true },
];

export const LENGTH_NO_USAGE: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { content: "partial" } }] } },
  { chunk: { choices: [{ index: 0, delta: {}, finish_reason: "length" }] } },
  { end: true },
];

export const SLOW: FakeScenario = [
  { chunk: { id: "c1", choices: [{ index: 0, delta: { content: "first" } }] }, delay: 100 },
  { chunk: { choices: [{ index: 0, delta: { content: "second" } }] }, delay: 1000 },
  { chunk: { choices: [{ index: 0, delta: {}, finish_reason: "stop" }] }, delay: 1000 },
  { end: true },
];

export const OVERFLOW_BODY = {
  message: "This model's maximum context length is 131072 tokens. However, you requested 200000 tokens.",
  type: "invalid_request_error",
};

export const WEATHER_TOOLS = [
  { name: "search", description: "search", schema: { type: "object", properties: {} } },
  { name: "fetch", description: "fetch", schema: { type: "object", properties: {} } },
];

/** One tool for the slow-callback tests (execution budgets are brain-side). */
export const SLOW_TOOL = { name: "get_image", description: "image", schema: { type: "object", properties: {} } };

/** The standard single `get_image` tool advertisement used by many scenarios. */
export const IMAGE_TOOL = [{ name: "get_image", description: "image", schema: { type: "object", properties: {} } }];
