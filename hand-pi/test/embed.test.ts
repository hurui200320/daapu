import type { Server } from "node:http";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { startFakeUpstream, type FakeScenario } from "./fake-upstream.js";
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

function embedRequest(upstreamPort: number, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    model: {
      baseUrl: `http://127.0.0.1:${upstreamPort}/v1`,
      apiKey: "test-key",
      modelId: "zenmux sub/google/gemini-embedding-2",
    },
    dimensions: 1536,
    input: ["hello world"],
    maxRetries: 1,
    timeoutMs: 30000,
    ...extra,
  };
}

async function embed(body: unknown, token = TOKEN): Promise<{ status: number; payload: unknown }> {
  const response = await fetch(`http://127.0.0.1:${port}/v1/embed`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-daapu-token": token },
    body: JSON.stringify(body),
  });
  let payload: unknown = null;
  try {
    payload = await response.json();
  } catch {
    // non-JSON body; the status is what matters then
  }
  return { status: response.status, payload };
}

const HAPPY: FakeScenario = {
  status: 200,
  body: {
    object: "list",
    data: [{ object: "embedding", index: 0, embedding: [0.25, -0.5] }],
    model: "google/gemini-embedding-2",
    usage: { prompt_tokens: 5, total_tokens: 5 },
  },
};

const HAPPY_NO_USAGE: FakeScenario = {
  status: 200,
  body: { object: "list", data: [{ object: "embedding", index: 0, embedding: [0.1] }] },
};

describe("POST /v1/embed", () => {
  it("rejects requests without a token", async () => {
    const upstream = await startFakeUpstream(HAPPY);
    try {
      const { status, payload } = await embed(embedRequest(upstream.port), "");
      expect(status).toBe(401);
      expect(payload).toMatchObject({ ok: false, error: { type: "auth" } });
      expect(upstream.connectionCount()).toBe(0);
    } finally {
      await upstream.close();
    }
  });

  it("rejects a malformed envelope with invalid_request", async () => {
    const upstream = await startFakeUpstream(HAPPY);
    const base = embedRequest(upstream.port);
    const cases: Record<string, unknown>[] = [
      {},
      { ...base, model: undefined },
      { ...base, model: "not-an-object" },
      { ...base, model: { baseUrl: "http://x/v1", apiKey: "k", modelId: " " } },
      { ...base, model: { baseUrl: "ftp://x/v1", apiKey: "k", modelId: "m" } },
      { ...base, model: { baseUrl: "not-a-url", apiKey: "k", modelId: "m" } },
      { ...base, model: { baseUrl: "http://x/v1", apiKey: " ", modelId: "m" } },
      { ...base, dimensions: 0 },
      { ...base, dimensions: "1536" },
      { ...base, input: [] },
      { ...base, input: [1, 2] },
      { ...base, input: ["ok", " "] },
      { ...base, maxRetries: -1 },
      { ...base, maxRetries: 1.5 },
      { ...base, timeoutMs: -1 },
      { ...base, timeoutMs: 1.5 },
    ];
    try {
      for (const body of cases) {
        const { status, payload } = await embed(body);
        expect(status).toBe(400);
        expect(payload).toMatchObject({ ok: false, error: { type: "invalid_request" } });
      }
      expect(upstream.connectionCount()).toBe(0);
    } finally {
      await upstream.close();
    }
  });

  it("relays vectors, dimensions, and usage and passes the model spec through", async () => {
    const upstream = await startFakeUpstream(HAPPY);
    try {
      const { status, payload } = await embed(embedRequest(upstream.port));
      expect(status).toBe(200);
      expect(payload).toEqual({
        vectors: [[0.25, -0.5]],
        dimensions: 2,
        usage: { promptTokens: 5, totalTokens: 5 },
      });
      // the gateway request carried the model, the input, the expected
      // dimensions, and the api key as a bearer token
      expect(upstream.captured()).toEqual({
        model: "zenmux sub/google/gemini-embedding-2",
        input: ["hello world"],
        dimensions: 1536,
      });
      expect(upstream.capturedHeaders()[0]?.authorization).toBe("Bearer test-key");
    } finally {
      await upstream.close();
    }
  });

  it("merges additionalProperties into the gateway request body", async () => {
    const upstream = await startFakeUpstream(HAPPY);
    try {
      const { status, payload } = await embed(
        embedRequest(upstream.port, { additionalProperties: { service_tier: "priority" } }),
      );
      expect(status).toBe(200);
      expect(payload).toMatchObject({ dimensions: 2 });
      // the extra knob rides the root level of the gateway request body,
      // exactly as the brain described it
      expect(upstream.captured()).toEqual({
        model: "zenmux sub/google/gemini-embedding-2",
        input: ["hello world"],
        dimensions: 1536,
        service_tier: "priority",
      });
    } finally {
      await upstream.close();
    }
  });

  it("rejects a non-object additionalProperties", async () => {
    const upstream = await startFakeUpstream(HAPPY);
    try {
      const base = embedRequest(upstream.port);
      for (const additionalProperties of ["priority", [1, 2], null, 42, true]) {
        const { status, payload } = await embed({ ...base, additionalProperties });
        expect(status).toBe(400);
        expect(payload).toMatchObject({ ok: false, error: { type: "invalid_request" } });
      }
      expect(upstream.connectionCount()).toBe(0);
    } finally {
      await upstream.close();
    }
  });

  it("rejects additionalProperties colliding with the hand-managed fields", async () => {
    // the hand must never let an extra property silently override (or be
    // overridden by) the fields it manages itself
    const upstream = await startFakeUpstream(HAPPY);
    try {
      const base = embedRequest(upstream.port);
      for (const key of ["model", "input", "dimensions"]) {
        const { status, payload } = await embed({
          ...base,
          additionalProperties: { [key]: "sneaky" },
        });
        expect(status).toBe(400);
        expect(payload).toMatchObject({
          ok: false,
          error: {
            type: "invalid_request",
            message: expect.stringContaining(`'${key}'`),
          },
        });
      }
      expect(upstream.connectionCount()).toBe(0);
    } finally {
      await upstream.close();
    }
  });

  it("omits usage when the provider does not report it", async () => {
    const upstream = await startFakeUpstream(HAPPY_NO_USAGE);
    try {
      const { status, payload } = await embed(embedRequest(upstream.port));
      expect(status).toBe(200);
      expect(payload).toEqual({ vectors: [[0.1]], dimensions: 1 });
    } finally {
      await upstream.close();
    }
  });

  it("omits usage when the provider reports only a partial usage object", async () => {
    // both prompt and total tokens are required; a partial report (e.g.
    // only prompt_tokens) is not a trustworthy usage object
    const upstream = await startFakeUpstream({
      status: 200,
      body: {
        object: "list",
        data: [{ object: "embedding", index: 0, embedding: [0.1] }],
        usage: { prompt_tokens: 5 },
      },
    });
    try {
      const { status, payload } = await embed(embedRequest(upstream.port));
      expect(status).toBe(200);
      expect(payload).toEqual({ vectors: [[0.1]], dimensions: 1 });
    } finally {
      await upstream.close();
    }
  });

  it("surfaces a malformed 2xx body as upstream", async () => {
    for (const body of [
      { data: [{ object: "embedding", index: 0, embedding: "nope" }] },
      { data: [{ object: "embedding", index: 0, embedding: ["0.5"] }] },
    ]) {
      const upstream = await startFakeUpstream({ status: 200, body });
      try {
        const { status, payload } = await embed(embedRequest(upstream.port));
        expect(status).toBe(502);
        expect(payload).toMatchObject({ ok: false, error: { type: "upstream" } });
      } finally {
        await upstream.close();
      }
    }
  });

  it("fails a gateway that returns vectors of inconsistent dimensions", async () => {
    // every vector must share the first one's length: a batch mixing
    // dimensions would silently misalign the brain's per-item vectors
    const upstream = await startFakeUpstream({
      status: 200,
      body: {
        object: "list",
        data: [
          { object: "embedding", index: 0, embedding: [0.1, 0.2] },
          { object: "embedding", index: 1, embedding: [0.3] },
        ],
      },
    });
    try {
      const { status, payload } = await embed(embedRequest(upstream.port, { input: ["a", "b"] }));
      expect(status).toBe(502);
      expect(payload).toMatchObject({
        ok: false,
        error: {
          type: "upstream",
          message: expect.stringContaining("1 dimensions; expected 2"),
        },
      });
    } finally {
      await upstream.close();
    }
  });

  it("fails a gateway that collapses the batch to fewer vectors than inputs", async () => {
    // the bifrost/zenmux embedding route returns ONE data entry regardless
    // of the input count: the hand must not silently misalign the brain's
    // per-item vector associations
    const upstream = await startFakeUpstream(HAPPY_NO_USAGE);
    try {
      const { status, payload } = await embed(embedRequest(upstream.port, { input: ["a", "b", "c"] }));
      expect(status).toBe(502);
      expect(payload).toMatchObject({
        ok: false,
        error: {
          type: "upstream",
          message: expect.stringContaining("returned 1 vectors for 3 inputs"),
        },
      });
    } finally {
      await upstream.close();
    }
  });

  it("realigns vectors by their index when the gateway answers out of order", async () => {
    // the OpenAI contract enumerates vectors by `index`, not wire order: a
    // gateway that reverses the batch must not misalign the brain's
    // per-item vector associations
    const upstream = await startFakeUpstream({
      status: 200,
      body: {
        object: "list",
        data: [
          { object: "embedding", index: 1, embedding: [0.2] },
          { object: "embedding", index: 0, embedding: [0.1] },
        ],
      },
    });
    try {
      const { status, payload } = await embed(embedRequest(upstream.port, { input: ["a", "b"] }));
      expect(status).toBe(200);
      expect(payload).toEqual({ vectors: [[0.1], [0.2]], dimensions: 1 });
    } finally {
      await upstream.close();
    }
  });

  it("fails a gateway that returns duplicate vector indexes", async () => {
    // duplicate indexes cannot be realigned onto the input items
    const upstream = await startFakeUpstream({
      status: 200,
      body: {
        object: "list",
        data: [
          { object: "embedding", index: 0, embedding: [0.1] },
          { object: "embedding", index: 0, embedding: [0.2] },
        ],
      },
    });
    try {
      const { status, payload } = await embed(embedRequest(upstream.port, { input: ["a", "b"] }));
      expect(status).toBe(502);
      expect(payload).toMatchObject({
        ok: false,
        error: {
          type: "upstream",
          message: expect.stringContaining("duplicate or out-of-range vector indexes"),
        },
      });
    } finally {
      await upstream.close();
    }
  });

  it("fails a gateway that omits the vector index", async () => {
    const upstream = await startFakeUpstream({
      status: 200,
      body: { object: "list", data: [{ object: "embedding", embedding: [0.1] }] },
    });
    try {
      const { status, payload } = await embed(embedRequest(upstream.port));
      expect(status).toBe(502);
      expect(payload).toMatchObject({
        ok: false,
        error: {
          type: "upstream",
          message: expect.stringContaining("without a valid index"),
        },
      });
    } finally {
      await upstream.close();
    }
  });

  it("surfaces a 4xx as invalid_request without retrying", async () => {
    const upstream = await startFakeUpstream([
      // array form + delay: a plain scenario, NOT an SSE step list
      { status: 400, body: { error: { message: "input is too long", type: "invalid_request_error" } }, delay: 0 },
    ]);
    try {
      const { status, payload } = await embed(embedRequest(upstream.port, { maxRetries: 5 }));
      expect(status).toBe(400);
      expect(payload).toMatchObject({
        ok: false,
        error: { type: "invalid_request", message: expect.stringContaining("input is too long") },
      });
      expect(upstream.connectionCount()).toBe(1);
    } finally {
      await upstream.close();
    }
  });

  it("forwards an oversized batch to the upstream and surfaces its rejection", async () => {
    // the hand imposes no batch-size cap: batch sizing is the brain's call.
    // an upstream that rejects the batch answers 4xx, which the hand maps
    // to invalid_request so the brain (the ELTM tool layer) can react.
    const oversized = Array.from({ length: 129 }, (_, i) => `fact ${i}`);
    const upstream = await startFakeUpstream({
      status: 400,
      body: { error: { message: "too many inputs in a single request" } },
    });
    try {
      const { status, payload } = await embed(embedRequest(upstream.port, { input: oversized, maxRetries: 5 }));
      expect(status).toBe(400);
      expect(payload).toMatchObject({
        ok: false,
        error: {
          type: "invalid_request",
          message: expect.stringContaining("too many inputs in a single request"),
        },
      });
      expect(upstream.captured()).toMatchObject({ input: oversized });
      expect(upstream.connectionCount()).toBe(1);
    } finally {
      await upstream.close();
    }
  });

  it("surfaces 401/403 as auth without retrying", async () => {
    for (const status of [401, 403]) {
      const upstream = await startFakeUpstream({ status, body: { error: { message: "bad key" } } });
      try {
        const { status: responseStatus, payload } = await embed(embedRequest(upstream.port, { maxRetries: 5 }));
        expect(responseStatus).toBe(401);
        expect(payload).toMatchObject({ ok: false, error: { type: "auth" } });
        expect(upstream.connectionCount()).toBe(1);
      } finally {
        await upstream.close();
      }
    }
  });

  it("surfaces 404/405 as upstream, not as the 'split your input' channel", async () => {
    // a 404/405 means the endpoint itself is wrong (a baseUrl
    // misconfiguration), not that the request content was rejected: it must
    // not ride the invalid_request path that tells the brain to split its
    // input. It answers upstream instead (retried against the caller's
    // budget; maxRetries 1 keeps this test to a single attempt).
    for (const status of [404, 405]) {
      const upstream = await startFakeUpstream({ status, body: { error: { message: "nope" } } });
      try {
        const { status: responseStatus, payload } = await embed(embedRequest(upstream.port, { maxRetries: 1 }));
        expect(responseStatus).toBe(502);
        expect(payload).toMatchObject({
          ok: false,
          error: {
            type: "upstream",
            message: expect.stringContaining(`HTTP ${status}`),
          },
        });
        expect(upstream.connectionCount()).toBe(1);
      } finally {
        await upstream.close();
      }
    }
  });

  it("retries a 429 rate limit and succeeds on the next attempt", async () => {
    // rate limiting is transient: it must ride the `upstream` retry path,
    // not the `invalid_request` "split your input" channel
    const upstream = await startFakeUpstream([{ status: 429, body: { error: { message: "rate limited" } } }, HAPPY]);
    try {
      const { status, payload } = await embed(embedRequest(upstream.port, { maxRetries: 2 }));
      expect(status).toBe(200);
      expect(payload).toMatchObject({ dimensions: 2 });
      expect(upstream.connectionCount()).toBe(2);
    } finally {
      await upstream.close();
    }
  });

  it("fails with upstream when a 429 exhausts the retries", async () => {
    const upstream = await startFakeUpstream({ status: 429, body: { error: { message: "rate limited" } } });
    try {
      const { status, payload } = await embed(embedRequest(upstream.port, { maxRetries: 2 }));
      expect(status).toBe(502);
      expect(payload).toMatchObject({
        ok: false,
        error: { type: "upstream", message: expect.stringContaining("maxRetries (2) exhausted") },
      });
      expect(upstream.connectionCount()).toBe(2);
    } finally {
      await upstream.close();
    }
  });

  it("retries a 5xx and succeeds on the next attempt", async () => {
    const upstream = await startFakeUpstream([{ status: 500, body: { error: { message: "boom" } } }, HAPPY]);
    try {
      const { status, payload } = await embed(embedRequest(upstream.port, { maxRetries: 2 }));
      expect(status).toBe(200);
      expect(payload).toMatchObject({ dimensions: 2 });
      expect(upstream.connectionCount()).toBe(2);
    } finally {
      await upstream.close();
    }
  });

  it("fails with upstream when the retries are exhausted", async () => {
    const upstream = await startFakeUpstream({ status: 503, body: { error: { message: "down" } } });
    try {
      const { status, payload } = await embed(embedRequest(upstream.port, { maxRetries: 2 }));
      expect(status).toBe(502);
      expect(payload).toMatchObject({
        ok: false,
        error: { type: "upstream", message: expect.stringContaining("maxRetries (2) exhausted") },
      });
      expect(upstream.connectionCount()).toBe(2);
    } finally {
      await upstream.close();
    }
  });

  it("times out a hanging gateway and fails with upstream", async () => {
    const upstream = await startFakeUpstream({ status: 200, body: HAPPY.body, delay: 800 });
    try {
      const { status, payload } = await embed(embedRequest(upstream.port, { timeoutMs: 100 }));
      expect(status).toBe(502);
      expect(payload).toMatchObject({
        ok: false,
        error: { type: "upstream", message: expect.stringContaining("timed out") },
      });
    } finally {
      await upstream.close();
    }
  }, 10_000);

  it("tears down a stalled upstream body when the per-attempt timeout fires", async () => {
    // the gateway answers the headers immediately but stalls the body:
    // the timeout must cancel the upstream read (and connection), not
    // leave it open for the gateway's delayed body — with unlimited
    // retries, an abandoned read would leak one connection per attempt
    const upstream = await startFakeUpstream({ status: 200, body: HAPPY.body, bodyDelay: 1000 });
    try {
      const closePromise = upstream.waitForClientClose();
      const { status, payload } = await embed(embedRequest(upstream.port, { timeoutMs: 100 }));
      expect(status).toBe(502);
      expect(payload).toMatchObject({
        ok: false,
        error: { type: "upstream", message: expect.stringContaining("timed out") },
      });
      // the upstream connection closed well before the gateway would
      // have finished its delayed body (1000 ms)
      await expect(
        Promise.race([closePromise, new Promise((resolve) => setTimeout(() => resolve("still-open"), 300))]),
      ).resolves.toBe(undefined);
    } finally {
      await upstream.close();
    }
  }, 10_000);

  it("closes the upstream body read when the client disconnects mid-response", async () => {
    // the gateway answers the headers immediately but stalls the body
    // for a second: a client disconnect in that window must tear down the
    // upstream read (and connection), not leave the hand waiting on a
    // dead client
    const upstream = await startFakeUpstream({ status: 200, body: HAPPY.body, bodyDelay: 1000 });
    try {
      const closePromise = upstream.waitForClientClose();
      const controller = new AbortController();
      const responsePromise = fetch(`http://127.0.0.1:${port}/v1/embed`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-daapu-token": TOKEN },
        body: JSON.stringify(embedRequest(upstream.port)),
        signal: controller.signal,
      });
      // the hand relays nothing until it has the full upstream body, so
      // the client is still waiting for its response when it disconnects
      await new Promise((resolve) => setTimeout(resolve, 50));
      controller.abort();
      await expect(responsePromise).rejects.toThrow();
      // the upstream connection closed well before the gateway would
      // have finished its delayed body (1000 ms)
      await expect(
        Promise.race([closePromise, new Promise((resolve) => setTimeout(() => resolve("still-open"), 300))]),
      ).resolves.toBe(undefined);
    } finally {
      await upstream.close();
    }
  }, 10_000);
});
