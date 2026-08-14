/**
 * Shared HTTP plumbing for the plain `node:http` server: JSON responses,
 * error-shape mapping, body reading with the request cap, and the abort
 * signal tied to client disconnects.
 */

import type { IncomingMessage, ServerResponse } from "node:http";
import { HandFailure } from "./types.js";

/** Request bodies are capped (default 50 MB — base64 images make multi-MB bodies normal). */
export const MAX_BODY_BYTES = 50 * 1024 * 1024;

export function respondJson(res: ServerResponse, status: number, payload: unknown): void {
  if (res.writableEnded) {
    return;
  }
  const body = JSON.stringify(payload);
  res.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(body) });
  res.end(body);
}

/** Maps any handler failure onto the `{ok:false,error}` contract. */
export function respondFailure(res: ServerResponse, error: unknown): void {
  if (res.headersSent) {
    // An SSE stream is already in flight; the run loop emits its own
    // terminal error event, so here we can only close the connection.
    res.end();
    return;
  }
  if (error instanceof HandFailure) {
    respondJson(res, statusForErrorType(error.handError.type), { ok: false, error: error.handError });
    return;
  }
  const message = error instanceof Error ? error.message : String(error);
  console.error(`[hand] internal error: ${message}`);
  respondJson(res, 500, { ok: false, error: { type: "internal", message } });
}

function statusForErrorType(type: string): number {
  switch (type) {
    case "invalid_request":
      return 400;
    case "auth":
      return 401;
    case "internal":
      return 500;
    default:
      return 200;
  }
}

export async function readBody(req: IncomingMessage, res: ServerResponse): Promise<string> {
  const declaredLength = Number(req.headers["content-length"] ?? "0");
  if (declaredLength > MAX_BODY_BYTES) {
    throw new HandFailure({
      type: "invalid_request",
      message: `request body exceeds the ${MAX_BODY_BYTES} byte cap`,
    });
  }
  const chunks: Buffer[] = [];
  let received = 0;
  for await (const chunk of req) {
    const buffer = chunk as Buffer;
    received += buffer.length;
    if (received > MAX_BODY_BYTES) {
      throw new HandFailure({
        type: "invalid_request",
        message: `request body exceeds the ${MAX_BODY_BYTES} byte cap`,
      });
    }
    chunks.push(buffer);
  }
  return Buffer.concat(chunks).toString("utf8");
}

/** Aborts upstream work when the client disconnects before the response finishes. */
export function requestAbortSignal(res: ServerResponse): AbortSignal {
  const controller = new AbortController();
  res.on("close", () => {
    if (!res.writableEnded) {
      controller.abort();
    }
  });
  return controller.signal;
}
