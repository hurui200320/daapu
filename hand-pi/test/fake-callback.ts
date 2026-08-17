import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";

export interface ToolCallbackRequest {
  runId: string;
  id: string;
  name: string;
  args: unknown;
}

export type ToolCallbackResponse =
  | { parts: unknown[]; isError: boolean }
  | { fatal: { message: string } };

/** A scripted `GET /tools` answer: a tool list, or a custom HTTP status. */
export type ToolListResponse =
  | { tools: unknown[] }
  | { status: number };

interface QueueEntry {
  delayMs: number;
  response: ToolCallbackResponse;
}

export interface FakeCallback {
  url: string;
  /** The tool-listing endpoint the hand queries before each LLM request. */
  toolsUrl: string;
  /** Respond to each callback with the next queued response. */
  scripted: (...responses: ToolCallbackResponse[]) => void;
  /** The next callback answers after `delayMs`, with the queued response. */
  scriptedDelayed: (delayMs: number, response: ToolCallbackResponse) => void;
  /** The next callback never answers (the caller must abort it). */
  scriptedHang: () => void;
  /**
   * Answer the next `GET /tools` calls with the queued responses; an empty
   * queue answers `{tools: []}` (no tools advertised).
   */
  scriptedToolList: (...responses: ToolListResponse[]) => void;
  /** The next `GET /tools` never answers (the caller must abort it). */
  scriptedToolListHang: () => void;
  /**
   * Resolves once at least `count` `GET /tools` requests have arrived.
   */
  waitForToolListRequests: (count: number) => Promise<void>;
  /** The runIds the hand queried via `GET /tools`, in order. */
  toolListRequests: () => string[];
  /** All callback requests received, in order. */
  requests: () => ToolCallbackRequest[];
  close: () => Promise<void>;
}

/**
 * Scripted tool-callback server: the hand POSTs tool executions here and
 * GETs the per-round tool list from `{url}/tools`. Callback responses are
 * popped from a queue; an empty queue returns a 500, and a `scriptedHang`
 * leaves the next request unanswered until the client gives up. Tool-list
 * responses are popped from their own queue; an empty queue returns an
 * empty list, and a `scriptedToolListHang` leaves the next listing
 * unanswered.
 */
export function startFakeCallback(): Promise<FakeCallback> {
  const requests: ToolCallbackRequest[] = [];
  let queue: QueueEntry[] = [];
  let hangNext = false;
  let toolQueue: ToolListResponse[] = [];
  let hangNextToolList = false;
  let toolListWaitTarget = 0;
  let resolveToolListWait: (() => void) | undefined;
  const toolListRequests: string[] = [];
  const server: Server = createServer((req: IncomingMessage, res: ServerResponse) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    if (req.method === "GET") {
      const runId = url.searchParams.get("runId") ?? "";
      toolListRequests.push(runId);
      if (toolListRequests.length >= toolListWaitTarget) {
        resolveToolListWait?.();
        resolveToolListWait = undefined;
      }
      if (hangNextToolList) {
        // never answer; the client's abort closes the connection
        hangNextToolList = false;
        return;
      }
      const entry = toolQueue.shift();
      if (entry !== undefined && "status" in entry) {
        res.writeHead(entry.status, { "content-type": "application/json" });
        res.end(JSON.stringify({ ok: false }));
        return;
      }
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(entry ?? { tools: [] }));
      return;
    }
    const chunks: Buffer[] = [];
    req.on("data", (chunk: Buffer) => chunks.push(chunk));
    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      try {
        requests.push(JSON.parse(raw) as ToolCallbackRequest);
      } catch {
        requests.push({ runId: "", id: "", name: "", args: raw });
      }
      if (hangNext) {
        // never answer; the client's abort closes the connection (note:
        // `req` 'close' fires right after a completed body, so it must not
        // be used to end the response here)
        hangNext = false;
        return;
      }
      const entry = queue.shift();
      if (entry === undefined) {
        res.writeHead(500, { "content-type": "application/json" });
        res.end(JSON.stringify({ fatal: { message: "unscripted callback" } }));
        return;
      }
      setTimeout(() => {
        const response = entry.response;
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify(response));
      }, entry.delayMs);
    });
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (address === null || typeof address === "string") {
        reject(new Error("fake callback failed to bind"));
        return;
      }
      resolve({
        url: `http://127.0.0.1:${address.port}`,
        toolsUrl: `http://127.0.0.1:${address.port}/tools`,
        scripted: (...responses) => {
          queue = [...queue, ...responses.map((response) => ({ delayMs: 0, response }))];
        },
        scriptedDelayed: (delayMs, response) => {
          queue = [...queue, { delayMs, response }];
        },
        scriptedHang: () => {
          hangNext = true;
        },
        scriptedToolList: (...responses) => {
          toolQueue = [...toolQueue, ...responses];
        },
        scriptedToolListHang: () => {
          hangNextToolList = true;
        },
        waitForToolListRequests: (count) =>
          new Promise<void>((resolve) => {
            if (toolListRequests.length >= count) {
              resolve();
              return;
            }
            toolListWaitTarget = count;
            resolveToolListWait = resolve;
          }),
        toolListRequests: () => [...toolListRequests],
        requests: () => [...requests],
        close: () =>
          new Promise<void>((resolveClose, rejectClose) => {
            server.close((error) => (error ? rejectClose(error) : resolveClose()));
          }),
      });
    });
  });
}
