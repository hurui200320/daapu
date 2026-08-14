/**
 * Scripted raw-SSE OpenAI-compatible upstream, for driving pi-ai end-to-end
 * in tests without the real gateway. Each instance serves ONE scenario: an
 * array of SSE steps or a plain HTTP error response.
 */

import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";

export interface SseStep {
  /** An OpenAI chunk object, emitted as `data: <json>`. */
  chunk?: unknown;
  /** A mid-stream `{"error": ...}` payload, emitted as `data: <json>`. */
  error?: unknown;
  /** Emit `data: [DONE]`. */
  end?: boolean;
  /** Delay before this step, in milliseconds. */
  delay?: number;
}

export type FakeScenario = SseStep[] | { status: number; body: unknown };

function isStepList(value: unknown[]): value is SseStep[] {
  if (value.length === 0) {
    return true;
  }
  const first = value[0];
  return (
    typeof first === "object" &&
    first !== null &&
    !Array.isArray(first) &&
    ("chunk" in first || "error" in first || "end" in first || "delay" in first)
  );
}

export interface FakeUpstream {
  port: number;
  /** The body of the last request received. */
  captured: () => unknown;
  /** The bodies of every request received, in order. */
  capturedAll: () => unknown[];
  /** The number of connections served so far. */
  connectionCount: () => number;
  /** Resolves when a client closes a response socket before the stream finished. */
  waitForClientClose: () => Promise<void>;
  close: () => Promise<void>;
}

/**
 * Scripted raw-SSE OpenAI-compatible upstream. Each connection consumes the
 * next scenario; the last scenario repeats forever. A single scenario (or a
 * single list of steps) can be passed directly.
 */
export function startFakeUpstream(
  scenarios: FakeScenario | FakeScenario[],
): Promise<FakeUpstream> {
  const queue: FakeScenario[] = Array.isArray(scenarios)
    ? isStepList(scenarios)
      ? [scenarios]
      : scenarios
    : [scenarios];
  const capturedBodies: unknown[] = [];
  let connectionCount = 0;
  let resolveClientClose: (() => void) | undefined;
  const server: Server = createServer((req: IncomingMessage, res: ServerResponse) => {
    connectionCount++;
    res.on("close", () => {
      if (!res.writableEnded) {
        resolveClientClose?.();
      }
    });
    const chunks: Buffer[] = [];
    req.on("data", (chunk: Buffer) => chunks.push(chunk));
    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      let body: unknown = raw;
      try {
        body = raw ? JSON.parse(raw) : null;
      } catch {
        body = raw;
      }
      capturedBodies.push(body);
      const scenario = queue.length > 1 ? queue.shift() : queue[0];
      if (scenario === undefined) {
        res.destroy();
        return;
      }
      void respond(res, scenario);
    });
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (address === null || typeof address === "string") {
        reject(new Error("fake upstream failed to bind"));
        return;
      }
      resolve({
        port: address.port,
        captured: () => capturedBodies.at(-1) ?? null,
        capturedAll: () => [...capturedBodies],
        connectionCount: () => connectionCount,
        waitForClientClose: () =>
          new Promise<void>((resolveClose) => {
            resolveClientClose = resolveClose;
          }),
        close: () =>
          new Promise<void>((resolveClose, rejectClose) => {
            server.close((error) => (error ? rejectClose(error) : resolveClose()));
          }),
      });
    });
  });
}

async function respond(res: ServerResponse, scenario: SseStep[] | { status: number; body: unknown }): Promise<void> {
  if (Array.isArray(scenario)) {
    res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
    for (const step of scenario) {
      if (step.delay !== undefined) {
        await new Promise((resolve) => setTimeout(resolve, step.delay));
      }
      if (step.chunk !== undefined) {
        res.write(`data: ${JSON.stringify(step.chunk)}\n\n`);
      } else if (step.error !== undefined) {
        res.write(`data: ${JSON.stringify(step.error)}\n\n`);
      } else if (step.end === true) {
        res.write("data: [DONE]\n\n");
      }
    }
    res.end();
  } else {
    res.writeHead(scenario.status, { "content-type": "application/json" });
    res.end(JSON.stringify(scenario.body));
  }
}
