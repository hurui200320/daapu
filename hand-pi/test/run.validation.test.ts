import { describe, expect, it } from "vitest";
import { startFakeUpstream } from "./fake-upstream.js";
import { NORMAL, runRequest, TOKEN, withTestServer } from "./helpers.js";
import { SERVICE_VERSION } from "../src/routes.js";

const { port } = withTestServer();

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
