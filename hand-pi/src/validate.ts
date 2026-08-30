/**
 * The ONLY place request JSON is parsed and validated. The hand trusts
 * Kotlin to send valid messages (Kotlin is the format's authority and
 * validates on encode), so handlers check the request envelope here and
 * nowhere else — every accepted shape of `/v1/run` and `/v1/embed` is
 * described by this file, one function per wire type.
 *
 * All functions are pure: they either return the validated value or throw
 * a `HandFailure` of type `invalid_request`, which the HTTP layer maps onto
 * the `{ok:false,error}` contract.
 */

import { HandFailure, type EmbedRequest, type ModelSpec, type RunRequest, type ToolSpec } from "./types.js";

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function failInvalid(message: string): never {
  throw new HandFailure({ type: "invalid_request", message });
}

export function parseBody(body: string): Record<string, unknown> {
  let raw: unknown;
  try {
    raw = JSON.parse(body);
  } catch (error) {
    failInvalid(`request body is not valid JSON: ${error instanceof Error ? error.message : String(error)}`);
  }
  if (!isRecord(raw)) {
    failInvalid("request body must be a JSON object");
  }
  return raw;
}

export function validateString(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    failInvalid(`${field} must be a non-blank string`);
  }
  return value;
}

export function validatePositiveInt(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value <= 0) {
    failInvalid(`${field} must be a positive integer`);
  }
  return value;
}

export function validateNonNegativeInt(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0) {
    failInvalid(`${field} must be a non-negative integer`);
  }
  return value;
}

export function validateHttpUrl(value: unknown, field: string): string {
  const url = validateString(value, field);
  if (!/^https?:\/\//.test(url)) {
    failInvalid(`${field} must be an http(s) URL`);
  }
  return url;
}

export function validateModelSpec(value: unknown): ModelSpec {
  if (!isRecord(value)) {
    failInvalid("model must be an object");
  }
  const baseUrl = validateString(value.baseUrl, "model.baseUrl");
  const apiKey = validateString(value.apiKey, "model.apiKey");
  const modelId = validateString(value.modelId, "model.modelId");
  const contextWindow = validatePositiveInt(value.contextWindow, "model.contextWindow");
  const maxOutputTokens = validatePositiveInt(value.maxOutputTokens, "model.maxOutputTokens");
  if (typeof value.reasoning !== "boolean") {
    failInvalid("model.reasoning must be a boolean");
  }
  const reasoningEffort =
    value.reasoningEffort === undefined ? undefined : validateString(value.reasoningEffort, "model.reasoningEffort");
  const input = validateInputModalities(value.input);
  return {
    baseUrl,
    apiKey,
    modelId,
    contextWindow,
    maxOutputTokens,
    reasoning: value.reasoning,
    reasoningEffort,
    input,
  };
}

function validateInputModalities(value: unknown): ("text" | "image")[] {
  if (!Array.isArray(value) || value.length === 0) {
    failInvalid("model.input must be a non-empty array");
  }
  const modalities: ("text" | "image")[] = [];
  for (const entry of value) {
    if (entry !== "text" && entry !== "image") {
      failInvalid(`model.input contains unsupported modality '${String(entry)}'`);
    }
    modalities.push(entry);
  }
  return modalities;
}

export function validateRunRequest(body: string): RunRequest {
  const raw = parseBody(body);
  const model = validateModelSpec(raw.model);
  // the hand trusts Kotlin to send valid messages (Kotlin validates on
  // encode); only the envelope is checked here
  const messages = raw.messages;
  if (!Array.isArray(messages)) {
    failInvalid("messages must be an array");
  }
  if (messages.length === 0) {
    failInvalid("messages must be a non-empty array");
  }
  const maxTokens = validatePositiveInt(raw.maxTokens, "maxTokens");
  const systemPrompt = raw.systemPrompt === undefined ? undefined : validateString(raw.systemPrompt, "systemPrompt");
  const runId = validateString(raw.runId, "runId");
  const maxRounds = validateNonNegativeInt(raw.maxRounds, "maxRounds");
  const maxRetries = validateNonNegativeInt(raw.maxRetries, "maxRetries");
  const streamIdleTimeoutMs = validateNonNegativeInt(raw.streamIdleTimeoutMs, "streamIdleTimeoutMs");
  // the tool set is not passed statically: the hand queries
  // `GET {toolListUrl}?runId=...` before every LLM request instead
  let toolListUrl: string | undefined;
  if (raw.toolListUrl !== undefined) {
    toolListUrl = validateHttpUrl(raw.toolListUrl, "toolListUrl");
  }
  let toolCallbackUrl: string | undefined;
  if (toolListUrl !== undefined) {
    toolCallbackUrl = validateHttpUrl(raw.toolCallbackUrl, "toolCallbackUrl");
  }
  return {
    model,
    messages,
    systemPrompt,
    maxTokens,
    runId,
    toolListUrl,
    toolCallbackUrl,
    maxRounds,
    maxRetries,
    streamIdleTimeoutMs,
  };
}

/**
 * Validates a `ToolSpec` array — the shape of both a request tool list
 * (gone from `/v1/run`) and the brain's `GET {toolListUrl}` response, which
 * the hand validates before every round.
 */
export function validateTools(value: unknown): ToolSpec[] {
  if (!Array.isArray(value)) {
    failInvalid("tools must be an array");
  }
  return value.map((rawTool, index) => {
    if (!isRecord(rawTool)) {
      failInvalid(`tools[${index}] must be an object`);
    }
    const name = validateString(rawTool.name, `tools[${index}].name`);
    const description = validateString(rawTool.description, `tools[${index}].description`);
    if (!isRecord(rawTool.schema)) {
      failInvalid(`tools[${index}].schema must be an object`);
    }
    return { name, description, schema: rawTool.schema };
  });
}

/** Mirrors `EmbeddingModel.RESERVED_GATEWAY_FIELDS` (`agent/model/EmbeddingModel.kt`). */
const RESERVED_GATEWAY_FIELDS = ["model", "input", "dimensions"] as const;

export function validateEmbedRequest(body: string): EmbedRequest {
  const raw = parseBody(body);
  const model = raw.model;
  if (!isRecord(model)) {
    failInvalid("model must be an object");
  }
  const baseUrl = validateHttpUrl(model.baseUrl, "model.baseUrl");
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
