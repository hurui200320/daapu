import type { ServerResponse } from "node:http";

/** The SSE event names (named events with JSON payloads), per spec §3.2. */
export type SseEventName =
  | "text_delta"
  | "reasoning_delta"
  | "assistant_message"
  | "tool_call"
  | "tool_result"
  | "retry"
  | "done"
  | "error";

export function writeSseHead(res: ServerResponse): void {
  res.writeHead(200, {
    "content-type": "text/event-stream",
    "cache-control": "no-cache",
    connection: "keep-alive",
  });
}

/** Keeps idle SSE connections alive at the proxy/server layer. */
export function writeSseComment(res: ServerResponse, text: string): void {
  res.write(`: ${text}\n\n`);
}

export function writeSseEvent(res: ServerResponse, event: SseEventName, payload: unknown): void {
  res.write(`event: ${event}\ndata: ${JSON.stringify(payload)}\n\n`);
}
