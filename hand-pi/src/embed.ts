/**
 * `/v1/embed`: one OpenAI-compatible embedding call through the hand. Like
 * `/v1/run`, the hand is stateless and opinionless — the brain describes
 * the embedding model, the expected output dimensionality, the
 * transient-retry budget, the per-attempt timeout, and any extra gateway
 * request properties on every request.
 *
 * The hand POSTs `{baseUrl}/embeddings` with the request's `apiKey` as a
 * bearer token and maps upstream failures onto its standard
 * `{ok:false,error:{...}}` taxonomy. Unlike `/v1/run`, this endpoint is
 * plain JSON (not SSE), so every error type gets a real HTTP status:
 * `invalid_request` → 400, `auth` → 401, `upstream` → 502.
 */

import type { ServerResponse } from "node:http";
import { backoffDelayMs, sleepOrAbort } from "./backoff.js";
import { failInvalid, isRecord } from "./convert.js";
import { respondJson } from "./http.js";
import {
  parseBody,
  validateNonNegativeInt,
  validatePositiveInt,
  validateString,
} from "./routes.js";
import { HandFailure, type EmbedRequest, type EmbedResult, type EmbedUsage } from "./types.js";

type EmbedOutcome =
  | { kind: "ok"; result: EmbedResult }
  | { kind: "failure"; error: HandFailure }
  | { kind: "abort" };

/** The fields the hand itself puts into the `{baseUrl}/embeddings` request body. */
const RESERVED_GATEWAY_FIELDS = ["model", "input", "dimensions"] as const;

export function validateEmbedRequest(body: string): EmbedRequest {
  const raw = parseBody(body);
  const model = raw.model;
  if (!isRecord(model)) {
    failInvalid("model must be an object");
  }
  const baseUrl = validateString(model.baseUrl, "model.baseUrl");
  if (!/^https?:\/\//.test(baseUrl)) {
    failInvalid("model.baseUrl must be an http(s) URL");
  }
  const apiKey = validateString(model.apiKey, "model.apiKey");
  const modelId = validateString(model.modelId, "model.modelId");
  const dimensions = validatePositiveInt(raw.dimensions, "dimensions");
  const input = raw.input;
  if (!Array.isArray(input) || input.length === 0) {
    failInvalid("input must be a non-empty array");
  }
  const texts: string[] = [];
  for (const [index, entry] of input.entries()) {
    if (typeof entry !== "string" || entry.trim().length === 0) {
      failInvalid(`input[${index}] must be a non-blank string`);
    }
    texts.push(entry);
  }
  const maxRetries = validateNonNegativeInt(raw.maxRetries, "maxRetries");
  const timeoutMs = validateNonNegativeInt(raw.timeoutMs, "timeoutMs");
  let additionalProperties: Record<string, unknown> | undefined;
  if (raw.additionalProperties !== undefined) {
    if (!isRecord(raw.additionalProperties)) {
      failInvalid("additionalProperties must be an object");
    }
    // the hand manages these gateway body fields itself: an extra
    // property with the same name would either silently override them or
    // be overridden — a brain bug either way, so fail it loudly
    for (const key of RESERVED_GATEWAY_FIELDS) {
      if (Object.prototype.hasOwnProperty.call(raw.additionalProperties, key)) {
        failInvalid(`additionalProperties must not override the '${key}' field`);
      }
    }
    additionalProperties = raw.additionalProperties;
  }
  return { model: { baseUrl, apiKey, modelId }, dimensions, input: texts, maxRetries, timeoutMs, additionalProperties };
}

/** `/v1/embed` is plain JSON, so it maps statuses itself (the shared SSE mapper defaults to 200). */
function embedStatusForErrorType(type: string): number {
  switch (type) {
    case "invalid_request":
      return 400;
    case "auth":
      return 401;
    case "upstream":
      return 502;
    default:
      return 500;
  }
}

export async function handleEmbed(
  res: ServerResponse,
  body: string,
  signal: AbortSignal,
): Promise<void> {
  try {
    const request = validateEmbedRequest(body);
    console.log(
      `[hand] embed start model=${request.model.modelId} items=${request.input.length}`,
    );
    const outcome = await embedWithRetry(request, signal);
    if (outcome.kind === "abort") {
      return;
    }
    if (outcome.kind === "failure") {
      respondJson(res, embedStatusForErrorType(outcome.error.handError.type), {
        ok: false,
        error: outcome.error.handError,
      });
      console.log(`[hand] embed end model=${request.model.modelId} error=${outcome.error.handError.type}`);
      return;
    }
    respondJson(res, 200, outcome.result);
    console.log(
      `[hand] embed end model=${request.model.modelId} dims=${outcome.result.dimensions} vectors=${outcome.result.vectors.length}`,
    );
  } catch (error) {
    if (error instanceof HandFailure) {
      respondJson(res, embedStatusForErrorType(error.handError.type), {
        ok: false,
        error: error.handError,
      });
      return;
    }
    if (signal.aborted) {
      return;
    }
    const message = error instanceof Error ? error.message : String(error);
    console.error(`[hand] internal error: ${message}`);
    respondJson(res, 500, { ok: false, error: { type: "internal", message } });
  }
}

/**
 * Retries transient failures (`upstream`: 5xx, 429, network, timeout) with
 * the same backoff the run loop uses. `maxRetries` semantics mirror
 * `/v1/run`: `0` = unlimited, otherwise the attempt cap (a `maxRetries` of
 * 1 allows a single attempt). Terminal failures (`auth`,
 * `invalid_request`) and the client disconnect abort the loop without
 * retrying.
 */
async function embedWithRetry(request: EmbedRequest, signal: AbortSignal): Promise<EmbedOutcome> {
  let attempt = 0;
  while (true) {
    attempt++;
    const outcome = await embedOnce(request, signal);
    if (outcome.kind !== "failure" || outcome.error.handError.type !== "upstream") {
      return outcome;
    }
    if (request.maxRetries > 0 && attempt >= request.maxRetries) {
      return {
        kind: "failure",
        error: new HandFailure({
          type: "upstream",
          message: `maxRetries (${request.maxRetries}) exhausted: ${outcome.error.handError.message}`,
        }),
      };
    }
    if (await sleepOrAbort(backoffDelayMs(attempt), signal)) {
      return { kind: "abort" };
    }
  }
}

/**
 * One attempt against `{baseUrl}/embeddings` (OpenAI-compatible). The
 * per-attempt `timeoutMs` budget covers the WHOLE attempt — response
 * headers AND the body read. A 2xx body is parsed into [EmbedResult];
 * everything else is mapped onto the hand's taxonomy: 401/403 → `auth`,
 * 429 → `upstream` (rate limiting is transient, retried like a 5xx),
 * 404/405 → `upstream` (the endpoint itself is wrong — a baseUrl
 * misconfiguration, not a content rejection), other 4xx →
 * `invalid_request` (the input-too-large channel), 5xx/network/timeout →
 * `upstream`.
 */
async function embedOnce(request: EmbedRequest, signal: AbortSignal): Promise<EmbedOutcome> {
  const timeoutController = new AbortController();
  const timer =
    request.timeoutMs > 0 ? setTimeout(() => timeoutController.abort(), request.timeoutMs) : undefined;
  // the baseUrl carries the `/v1` root; join so the path is preserved
  const url = new URL("embeddings", request.model.baseUrl.endsWith("/") ? request.model.baseUrl : `${request.model.baseUrl}/`);
  let response: Response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${request.model.apiKey}`,
      },
      body: JSON.stringify({
        model: request.model.modelId,
        input: request.input,
        dimensions: request.dimensions,
        // extra gateway knobs ride the root level of the request body,
        // exactly as the brain described them (collisions already rejected
        // during validation)
        ...request.additionalProperties,
      }),
      signal: AbortSignal.any([signal, timeoutController.signal]),
    });
  } catch (error) {
    if (signal.aborted) {
      return { kind: "abort" };
    }
    return {
      kind: "failure",
      error: new HandFailure({
        type: "upstream",
        message: timeoutController.signal.aborted
          ? `embedding request timed out after ${request.timeoutMs} ms`
          : `embedding request failed: ${error instanceof Error ? error.message : String(error)}`,
      }),
    };
  }
  try {
    if (response.status === 401 || response.status === 403) {
      return {
        kind: "failure",
        error: new HandFailure({
          type: "auth",
          message: `embedding gateway rejected the api key (HTTP ${response.status})`,
        }),
      };
    }
    if (response.status === 429) {
      const text = await readBody(response, request.timeoutMs, timeoutController, signal);
      return {
        kind: "failure",
        error: new HandFailure({
          type: "upstream",
          message: `embedding gateway rate limited (HTTP 429): ${text.slice(0, 200)}`,
        }),
      };
    }
    if (response.status === 404 || response.status === 405) {
      const text = await readBody(response, request.timeoutMs, timeoutController, signal);
      return {
        kind: "failure",
        error: new HandFailure({
          type: "upstream",
          message: `embedding gateway endpoint missing or method not allowed (HTTP ${response.status}): ${text.slice(0, 200)}`,
        }),
      };
    }
    if (response.status >= 400 && response.status < 500) {
      const text = await readBody(response, request.timeoutMs, timeoutController, signal);
      return {
        kind: "failure",
        error: new HandFailure({
          type: "invalid_request",
          message: `embedding gateway rejected the request (HTTP ${response.status}): ${text.slice(0, 200)}`,
        }),
      };
    }
    if (response.status >= 500) {
      const text = await readBody(response, request.timeoutMs, timeoutController, signal);
      return {
        kind: "failure",
        error: new HandFailure({
          type: "upstream",
          message: `embedding gateway failed (HTTP ${response.status}): ${text.slice(0, 200)}`,
        }),
      };
    }
    if (response.status !== 200) {
      return {
        kind: "failure",
        error: new HandFailure({
          type: "upstream",
          message: `embedding gateway returned HTTP ${response.status}`,
        }),
      };
    }
    let payload: unknown;
    try {
      payload = JSON.parse(await readBody(response, request.timeoutMs, timeoutController, signal));
    } catch {
      return {
        kind: "failure",
        error: new HandFailure({ type: "upstream", message: "embedding gateway returned a non-JSON body" }),
      };
    }
    return parseEmbedPayload(payload, request.input.length);
  } catch (error) {
    // the only failures left are body reads: a fired per-attempt timeout
    // (rejected by [readBody]), a client disconnect during the read
    // (aborted via [readBody]'s cancel), or a connection drop after the
    // headers
    if (signal.aborted) {
      return { kind: "abort" };
    }
    return {
      kind: "failure",
      error: new HandFailure({
        type: "upstream",
        message: timeoutController.signal.aborted
          ? `embedding request timed out after ${request.timeoutMs} ms`
          : error instanceof Error
            ? error.message
            : String(error),
      }),
    };
  } finally {
    if (timer !== undefined) {
      clearTimeout(timer);
    }
  }
}

/**
 * Reads the full response body under the per-attempt timeout (which stays
 * armed from the fetch through the read, so a gateway that answers the
 * headers and then stalls the body is still bounded). A client disconnect
 * OR the per-attempt timeout cancels the upstream body read — abandoning
 * `response.text()` instead would keep the upstream connection alive until
 * the gateway finishes (a stalled-body gateway would then leak one
 * connection per retried attempt). Rejects on timeout, client abort, or a
 * torn-down connection.
 */
async function readBody(
  response: Response,
  timeoutMs: number,
  timeoutController: AbortController,
  signal: AbortSignal,
): Promise<string> {
  const body = response.body;
  if (signal.aborted) {
    // the disconnect fired before the read started (or the listener below
    // would never fire for an already-aborted signal)
    void body?.cancel().catch(() => {});
    throw new Error("embedding request aborted");
  }
  const onClientAbort = () => {
    // tear down the upstream read, not just abandon it
    void body?.cancel().catch(() => {});
  };
  const onTimeoutAbort = () => {
    // same teardown when the per-attempt timeout fires mid-read: the race
    // below rejects, but the body read must not keep the connection open
    void body?.cancel().catch(() => {});
  };
  signal.addEventListener("abort", onClientAbort, { once: true });
  timeoutController.signal.addEventListener("abort", onTimeoutAbort, { once: true });
  try {
    if (timeoutMs <= 0) {
      return await response.text();
    }
    return await Promise.race([
      response.text(),
      rejectOnAbort(timeoutController.signal, `embedding request timed out after ${timeoutMs} ms`),
    ]);
  } finally {
    signal.removeEventListener("abort", onClientAbort);
    timeoutController.signal.removeEventListener("abort", onTimeoutAbort);
  }
}

/** Rejects when the signal aborts; used to bound `response.text()`. */
function rejectOnAbort(signal: AbortSignal, message: string): Promise<never> {
  return new Promise((_, reject) => {
    if (signal.aborted) {
      reject(new Error(message));
      return;
    }
    const onAbort = () => reject(new Error(message));
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

/**
 * Maps an OpenAI-compatible embeddings body onto [EmbedResult]. The result
 * MUST carry one vector per input item, realigned by the provider's
 * `index`: a gateway that collapses the batch (e.g. one `data` entry for
 * several inputs), duplicates indexes, or gaps them is an upstream
 * anomaly — a silent short circuit would misalign the brain's per-item
 * vector associations.
 */
function parseEmbedPayload(payload: unknown, expectedCount: number): EmbedOutcome {
  if (!isRecord(payload)) {
    return {
      kind: "failure",
      error: new HandFailure({ type: "upstream", message: "embedding gateway returned a non-object body" }),
    };
  }
  const data = payload.data;
  if (!Array.isArray(data) || data.length === 0) {
    return {
      kind: "failure",
      error: new HandFailure({ type: "upstream", message: "embedding gateway returned no vectors" }),
    };
  }
  const indexed: { index: number; vector: number[] }[] = [];
  for (const entry of data) {
    if (!isRecord(entry) || !Array.isArray(entry.embedding)) {
      return {
        kind: "failure",
        error: new HandFailure({ type: "upstream", message: "embedding gateway returned a malformed vector" }),
      };
    }
    const embedding: number[] = [];
    for (const value of entry.embedding) {
      if (typeof value !== "number") {
        return {
          kind: "failure",
          error: new HandFailure({ type: "upstream", message: "embedding gateway returned a malformed vector" }),
        };
      }
      embedding.push(value);
    }
    const index = entry.index;
    if (typeof index !== "number" || !Number.isInteger(index) || index < 0) {
      return {
        kind: "failure",
        error: new HandFailure({
          type: "upstream",
          message: "embedding gateway returned a vector without a valid index",
        }),
      };
    }
    indexed.push({ index, vector: embedding });
  }
  if (indexed.length !== expectedCount) {
    return {
      kind: "failure",
      error: new HandFailure({
        type: "upstream",
        message: `embedding gateway returned ${indexed.length} vectors for ${expectedCount} inputs (one vector per input is required)`,
      }),
    };
  }
  // The OpenAI contract enumerates vectors by `index`, not wire order:
  // realign by it so the vectors line up with the input items even when a
  // gateway answers out of order. Duplicate or gapped indexes cannot be
  // realigned and fail as an upstream anomaly.
  indexed.sort((a, b) => a.index - b.index);
  for (let i = 0; i < indexed.length; i++) {
    if (indexed[i]?.index !== i) {
      return {
        kind: "failure",
        error: new HandFailure({
          type: "upstream",
          message: "embedding gateway returned duplicate or out-of-range vector indexes",
        }),
      };
    }
  }
  const vectors = indexed.map((entry) => entry.vector);
  const dimensions = vectors[0]?.length ?? 0;
  if (dimensions === 0) {
    return {
      kind: "failure",
      error: new HandFailure({ type: "upstream", message: "embedding gateway returned an empty vector" }),
    };
  }
  for (const vector of vectors) {
    if (vector.length !== dimensions) {
      return {
        kind: "failure",
        error: new HandFailure({
          type: "upstream",
          message: `embedding gateway returned a vector of ${vector.length} dimensions; expected ${dimensions}`,
        }),
      };
    }
  }
  let usage: EmbedUsage | undefined;
  if (isRecord(payload.usage)) {
    const promptTokens = payload.usage.prompt_tokens;
    const totalTokens = payload.usage.total_tokens;
    if (typeof promptTokens === "number" && typeof totalTokens === "number") {
      usage = { promptTokens, totalTokens };
    }
  }
  return { kind: "ok", result: { vectors, dimensions, usage } };
}
