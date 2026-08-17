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

/** A gated batch: hold the first `count` callbacks until all have arrived. */
interface GateEntry {
  count: number;
  responder: (request: ToolCallbackRequest) => ToolCallbackResponse;
}

/** A request held open while a gated batch is still filling. */
interface HeldRequest {
  res: ServerResponse;
  request: ToolCallbackRequest;
}

/** The in-progress gated batch, filling with held requests. */
interface InFlightGate {
  expected: number;
  responder: (request: ToolCallbackRequest) => ToolCallbackResponse;
  held: HeldRequest[];
}

export interface FakeCallback {
  url: string;
  /** The tool-listing endpoint the hand queries before each LLM request. */
  toolsUrl: string;
  /** Respond to each callback with the next queued response. */
  scripted: (...responses: ToolCallbackResponse[]) => void;
  /** The next callback answers after `delayMs`, with the queued response. */
  scriptedDelayed: (delayMs: number, response: ToolCallbackResponse) => void;
  /**
   * The next `count` callbacks wait until all `count` requests have arrived,
   * then each is answered with `responder(request)`. A deterministic proof
   * that the hand fired the callbacks concurrently (none of them could answer
   * before every request was in flight); the responder keys the response on
   * the call's identity, since parallel arrival order is not guaranteed.
   *
   * A gate only opens when the queue is empty (the queue is checked first),
   * so it must not be mixed with `scripted`/`scriptedDelayed` — a mix throws
   * at setup time instead of silently mis-answering the gate's requests.
   */
  scriptedGate: (count: number, responder: (request: ToolCallbackRequest) => ToolCallbackResponse) => void;
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
  let gates: GateEntry[] = [];
  let inFlightGate: InFlightGate | undefined;
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
        // a gated batch holds its requests until the whole batch has
        // arrived, then each is answered with the batch's responder
        if (inFlightGate === undefined) {
          const gate = gates.shift();
          if (gate !== undefined) {
            inFlightGate = { expected: gate.count, responder: gate.responder, held: [] };
          }
        }
        if (inFlightGate !== undefined) {
          inFlightGate.held.push({ res, request: requests[requests.length - 1] });
          if (inFlightGate.held.length === inFlightGate.expected) {
            const held = inFlightGate.held;
            const responder = inFlightGate.responder;
            inFlightGate = undefined;
            for (const heldEntry of held) {
              const response = responder(heldEntry.request);
              heldEntry.res.writeHead(200, { "content-type": "application/json" });
              heldEntry.res.end(JSON.stringify(response));
            }
          }
          // hold this request unanswered until the whole batch has arrived
          return;
        }
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
          if (inFlightGate !== undefined || gates.length > 0) {
            throw new Error(
              "scripted: a scriptedGate batch is pending; the queue is checked before the gate, " +
                "so mixing would silently steal the gate's requests",
            );
          }
          queue = [...queue, ...responses.map((response) => ({ delayMs: 0, response }))];
        },
        scriptedDelayed: (delayMs, response) => {
          if (inFlightGate !== undefined || gates.length > 0) {
            throw new Error(
              "scriptedDelayed: a scriptedGate batch is pending; the queue is checked before the gate, " +
                "so mixing would silently steal the gate's requests",
            );
          }
          queue = [...queue, { delayMs, response }];
        },
        scriptedGate: (count, responder) => {
          if (inFlightGate !== undefined || queue.length > 0) {
            throw new Error(
              "scriptedGate: an earlier gate is still open or queued responses are pending; " +
                "the queue is checked before the gate, so mixing would silently steal the gate's requests",
            );
          }
          gates = [...gates, { count, responder }];
        },
        scriptedHang: () => {
          if (inFlightGate !== undefined || gates.length > 0 || queue.length > 0) {
            throw new Error(
              "scriptedHang: a scriptedGate batch or queued responses are pending; the hang is " +
                "checked before the queue and the gate, so mixing would silently steal them",
            );
          }
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
