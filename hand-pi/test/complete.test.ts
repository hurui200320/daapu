import type { Server } from "node:http";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { startFakeUpstream } from "./fake-upstream.js";
import { startServer } from "../src/main.js";

const TOKEN = "test-token";

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

async function complete(body: unknown): Promise<{ status: number; body: unknown }> {
  const response = await fetch(`http://127.0.0.1:${port}/v1/complete`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
    body: JSON.stringify(body),
  });
  return { status: response.status, body: await response.json() };
}

const NORMAL_SCENARIO = [
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

describe("/v1/health", () => {
  it("answers with the service version", async () => {
    const response = await fetch(`http://127.0.0.1:${port}/v1/health`, {
      headers: { "x-daapu-token": TOKEN },
    });
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true, version: "0.1.0" });
  });

  it("rejects requests without a token", async () => {
    const response = await fetch(`http://127.0.0.1:${port}/v1/health`);
    expect(response.status).toBe(401);
    expect(await response.json()).toMatchObject({ ok: false, error: { type: "auth" } });
  });
});

describe("POST /v1/complete", () => {
  it("returns a daapu-shaped assistant message with reasoning and folded usage", async () => {
    const upstream = await startFakeUpstream(NORMAL_SCENARIO);
    try {
      const { status, body } = await complete({
        model: modelSpec(upstream.port, { reasoningEffort: "high" }),
        messages: [{ role: "user", parts: [{ type: "text", text: "hi" }] }],
      });
      expect(status).toBe(200);
      expect(body).toEqual({
        ok: true,
        finishReason: "stop",
        message: {
          role: "assistant",
          parts: [
            { type: "reasoning", content: "thinking hard" },
            { type: "text", text: "Hello" },
          ],
          meta: {
            inputTokens: 100,
            outputTokens: 20,
            totalTokens: 120,
            modelId: "cerebras/gpt-oss-120b",
          },
          finishReason: "stop",
        },
      });
      const captured = upstream.captured() as Record<string, unknown>;
      expect(captured.model).toBe("cerebras/gpt-oss-120b");
      expect(captured.reasoning_effort).toBe("high");
      expect(captured.max_completion_tokens ?? captured.max_tokens).toBe(40000);
      expect(captured.stream).toBe(true);
      expect(captured.tools).toBeUndefined();
    } finally {
      await upstream.close();
    }
  });

  it("sends tool schemas when tools are provided", async () => {
    const upstream = await startFakeUpstream(NORMAL_SCENARIO);
    try {
      const { status, body } = await complete({
        model: modelSpec(upstream.port),
        messages: [{ role: "user", parts: [{ type: "text", text: "hi" }] }],
        tools: [{ name: "get_weather", description: "weather", schema: { type: "object", properties: {} } }],
      });
      expect(status).toBe(200);
      expect(body).toMatchObject({ ok: true });
      const captured = upstream.captured() as { tools?: { function: { name: string } }[] };
      expect(captured.tools?.map((tool) => tool.function.name)).toEqual(["get_weather"]);
    } finally {
      await upstream.close();
    }
  });

  it("classifies a length finish with a crowded prompt as context_exhausted", async () => {
    const upstream = await startFakeUpstream([
      { chunk: { choices: [{ index: 0, delta: { content: "partial" } }] } },
      {
        chunk: {
          choices: [{ index: 0, delta: {}, finish_reason: "length" }],
          usage: { prompt_tokens: 100000, completion_tokens: 300, total_tokens: 100300 },
        },
      },
      { end: true },
    ]);
    try {
      const { status, body } = await complete({
        model: modelSpec(upstream.port),
        messages: [{ role: "user", parts: [{ type: "text", text: "hi" }] }],
      });
      expect(status).toBe(200);
      expect(body).toMatchObject({ ok: false, error: { type: "context_exhausted" } });
    } finally {
      await upstream.close();
    }
  });

  it("classifies a length finish with a small prompt as output_budget_exhausted", async () => {
    const upstream = await startFakeUpstream([
      { chunk: { choices: [{ index: 0, delta: { content: "partial" } }] } },
      {
        chunk: {
          choices: [{ index: 0, delta: {}, finish_reason: "length" }],
          usage: { prompt_tokens: 1000, completion_tokens: 40000, total_tokens: 41000 },
        },
      },
      { end: true },
    ]);
    try {
      const { status, body } = await complete({
        model: modelSpec(upstream.port),
        messages: [{ role: "user", parts: [{ type: "text", text: "hi" }] }],
      });
      expect(status).toBe(200);
      expect(body).toMatchObject({ ok: false, error: { type: "output_budget_exhausted" } });
    } finally {
      await upstream.close();
    }
  });

  it("classifies a gateway-side overflow rejection as context_exhausted", async () => {
    const upstream = await startFakeUpstream({
      status: 400,
      body: {
        message: "This model's maximum context length is 131072 tokens. However, you requested 200000 tokens.",
        type: "invalid_request_error",
      },
    });
    try {
      const { status, body } = await complete({
        model: modelSpec(upstream.port),
        messages: [{ role: "user", parts: [{ type: "text", text: "hi" }] }],
      });
      expect(status).toBe(200);
      expect(body).toMatchObject({ ok: false, error: { type: "context_exhausted" } });
    } finally {
      await upstream.close();
    }
  });

  it("classifies a content_filter finish as content_filter", async () => {
    const upstream = await startFakeUpstream([
      { chunk: { id: "c1", choices: [{ index: 0, delta: { content: "no" } }] } },
      { chunk: { choices: [{ index: 0, delta: {}, finish_reason: "content_filter" }] } },
      { end: true },
    ]);
    try {
      const { status, body } = await complete({
        model: modelSpec(upstream.port),
        messages: [{ role: "user", parts: [{ type: "text", text: "hi" }] }],
      });
      expect(status).toBe(200);
      expect(body).toMatchObject({ ok: false, error: { type: "content_filter" } });
    } finally {
      await upstream.close();
    }
  });

  it("rejects an invalid model spec with 400", async () => {
    const upstream = await startFakeUpstream(NORMAL_SCENARIO);
    try {
      const { status, body } = await complete({
        model: modelSpec(upstream.port, { contextWindow: -1 }),
        messages: [],
      });
      expect(status).toBe(400);
      expect(body).toMatchObject({ ok: false, error: { type: "invalid_request" } });
    } finally {
      await upstream.close();
    }
  });

  it("rejects malformed JSON with 400", async () => {
    const response = await fetch(`http://127.0.0.1:${port}/v1/complete`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
      body: "not json",
    });
    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({ ok: false, error: { type: "invalid_request" } });
  });
});
