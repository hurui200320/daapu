/**
 * hand-pi entrypoint: environment, the plain `node:http` server, token check,
 * and request dispatch. The hand is stateless and opinionless — no catalog,
 * no config file, no content logging.
 */

import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { pathToFileURL } from "node:url";
import { handleEmbed } from "./embed.js";
import { readBody, requestAbortSignal, respondFailure, respondJson } from "./http.js";
import { handleHealth, handleRun } from "./routes.js";

const HAND_PORT = Number(process.env.HAND_PORT ?? "3100");
// bind address: 127.0.0.1 keeps the local development posture (only this
// machine reaches the hand); the docker deployment overrides it with 0.0.0.0
// so the brain container can reach the hand over the compose network
const HAND_HOST = process.env.HAND_HOST ?? "127.0.0.1";
const HAND_TOKEN = process.env.HAND_TOKEN ?? "";

export function startServer(port: number, token: string, host: string = "127.0.0.1"): Promise<Server> {
  // fail fast: a blank token would leave the run/embed routes unauthenticated
  // (mirrors hand.token's blank rejection on the Kotlin side); the entrypoint
  // surfaces the rejection below as a startup failure
  if (token.trim().length === 0) {
    return Promise.reject(new Error("HAND_TOKEN must not be blank: set the HAND_TOKEN environment variable"));
  }
  const server = createServer((req: IncomingMessage, res: ServerResponse) => {
    void handleRequest(req, res, token).catch((error: unknown) => {
      respondFailure(res, error);
    });
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, host, () => resolve(server));
  });
}

async function handleRequest(req: IncomingMessage, res: ServerResponse, token: string): Promise<void> {
  const url = new URL(req.url ?? "/", "http://localhost");
  // /v1/health is dispatched BEFORE the token check on purpose: it is the
  // docker/k8s probe endpoint (probes carry no secrets), answers only
  // {ok, version}, and performs no work — the auth boundary below protects
  // the run/embed routes, which trigger LLM execution
  if (req.method === "GET" && url.pathname === "/v1/health") {
    handleHealth(res);
    return;
  }
  if (req.headers["x-daapu-token"] !== token) {
    respondJson(res, 401, {
      ok: false,
      error: { type: "auth", message: "invalid or missing x-daapu-token" },
    });
    return;
  }
  if (req.method === "POST" && url.pathname === "/v1/run") {
    const body = await readBody(req);
    await handleRun(res, body, token, requestAbortSignal(res));
    return;
  }
  if (req.method === "POST" && url.pathname === "/v1/embed") {
    const body = await readBody(req);
    await handleEmbed(res, body, requestAbortSignal(res));
    return;
  }
  respondJson(res, 404, {
    ok: false,
    error: { type: "invalid_request", message: `no route for ${req.method ?? "?"} ${url.pathname}` },
  });
}

// pathToFileURL (not string interpolation) so executable paths with spaces
// or non-ASCII characters still compare equal to import.meta.url
const isEntrypoint = process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (isEntrypoint) {
  startServer(HAND_PORT, HAND_TOKEN, HAND_HOST)
    .then(() => {
      console.log(`[hand] listening on ${HAND_HOST}:${HAND_PORT}`);
    })
    .catch((error: unknown) => {
      console.error("[hand] failed to start:", error);
      process.exit(1);
    });
}
