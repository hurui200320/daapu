import type { ServerResponse } from "node:http";
import { respondJson } from "./http.js";
import { executeRun } from "./run.js";
import { validateRunRequest } from "./validate.js";

export const SERVICE_VERSION = "0.1.0";

export function handleHealth(res: ServerResponse): void {
  respondJson(res, 200, { ok: true, version: SERVICE_VERSION });
}

/** `/v1/run`: SSE round loop. See `executeRun` for the event contract. */
export async function handleRun(res: ServerResponse, body: string, token: string, signal: AbortSignal): Promise<void> {
  const request = validateRunRequest(body);
  console.log(`[hand] run start runId=${request.runId} model=${request.model.modelId}`);
  await executeRun(res, request, token, signal);
}
