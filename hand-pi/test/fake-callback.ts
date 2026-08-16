import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";

export interface ToolCallbackRequest {
  runId: string;
  chatId: string;
  id: string;
  name: string;
  args: string;
  /** The advertised tool budget, echoed back to the brain. */
  timeoutSeconds: number;
}

export type ToolCallbackResponse =
  | { parts: unknown[]; isError: boolean }
  | { fatal: { message: string } };

export interface FakeCallback {
  port: number;
  url: string;
  /** Respond to each callback with the next queued response. */
  scripted: (...responses: ToolCallbackResponse[]) => void;
  /** The next callback never answers (the caller must abort it). */
  scriptedHang: () => void;
  /** All callback requests received, in order. */
  requests: () => ToolCallbackRequest[];
  close: () => Promise<void>;
}

/**
 * Scripted tool-callback server: the hand POSTs tool executions here.
 * Responses are popped from a queue; an empty queue returns a 500, and a
 * `scriptedHang` leaves the next request unanswered until the client gives
 * up.
 */
export function startFakeCallback(): Promise<FakeCallback> {
  const requests: ToolCallbackRequest[] = [];
  let queue: ToolCallbackResponse[] = [];
  let hangNext = false;
  const server: Server = createServer((req: IncomingMessage, res: ServerResponse) => {
    const chunks: Buffer[] = [];
    req.on("data", (chunk: Buffer) => chunks.push(chunk));
    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      try {
        requests.push(JSON.parse(raw) as ToolCallbackRequest);
      } catch {
        requests.push({ runId: "", chatId: "", id: "", name: "", args: raw });
      }
      if (hangNext) {
        // never answer; the client's abort closes the connection (note:
        // `req` 'close' fires right after a completed body, so it must not
        // be used to end the response here)
        hangNext = false;
        return;
      }
      const response = queue.shift();
      if (response === undefined) {
        res.writeHead(500, { "content-type": "application/json" });
        res.end(JSON.stringify({ fatal: { message: "unscripted callback" } }));
        return;
      }
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(response));
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
        port: address.port,
        url: `http://127.0.0.1:${address.port}`,
        scripted: (...responses) => {
          queue = [...queue, ...responses];
        },
        scriptedHang: () => {
          hangNext = true;
        },
        requests: () => [...requests],
        close: () =>
          new Promise<void>((resolveClose, rejectClose) => {
            server.close((error) => (error ? rejectClose(error) : resolveClose()));
          }),
      });
    });
  });
}
