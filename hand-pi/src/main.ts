/**
 * hand-pi entrypoint: environment, the plain `node:http` server, token check,
 * and request dispatch. The hand is stateless and opinionless — no catalog,
 * no config file, no content logging.
 */

import {createServer, type IncomingMessage, type Server, type ServerResponse} from "node:http";
import {readBody, requestAbortSignal, respondFailure, respondJson} from "./http.js";
import {handleHealth, handleRun} from "./routes.js";

const HAND_PORT = Number(process.env.HAND_PORT ?? "3100");
const HAND_TOKEN = process.env.HAND_TOKEN ?? "";

export function startServer(port: number, token: string): Promise<Server> {
  const server = createServer((req: IncomingMessage, res: ServerResponse) => {
    void handleRequest(req, res, token).catch((error: unknown) => {
      respondFailure(res, error);
    });
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => resolve(server));
  });
}

async function handleRequest(req: IncomingMessage, res: ServerResponse, token: string): Promise<void> {
  if (req.headers["x-daapu-token"] !== token) {
    respondJson(res, 401, {
      ok: false,
      error: {type: "auth", message: "invalid or missing x-daapu-token"}
    });
    return;
  }
  const url = new URL(req.url ?? "/", "http://localhost");
  if (req.method === "GET" && url.pathname === "/v1/health") {
    handleHealth(res);
    return;
  }
  if (req.method === "POST" && url.pathname === "/v1/run") {
    const body = await readBody(req);
    await handleRun(res, body, token, requestAbortSignal(res));
    return;
  }
  respondJson(res, 404, {
    ok: false,
    error: {type: "invalid_request", message: `no route for ${req.method ?? "?"} ${url.pathname}`},
  });
}

const isEntrypoint = process.argv[1] !== undefined && import.meta.url === new URL(`file://${process.argv[1]}`).href;

if (isEntrypoint) {
  if (HAND_TOKEN.length == 0) {
    console.warn("[hand] HAND_TOKEN environment variable is empty, this is not secure");
  }
  startServer(HAND_PORT, HAND_TOKEN)
    .then(() => {
      console.log(`[hand] listening on 127.0.0.1:${HAND_PORT}`);
    })
    .catch((error: unknown) => {
      console.error("[hand] failed to start:", error);
      process.exit(1);
    });
}
