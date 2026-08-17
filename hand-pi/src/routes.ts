import type { ServerResponse } from "node:http";
import { failInvalid, isRecord } from "./convert.js";
import { respondJson } from "./http.js";
import { executeRun } from "./run.js";
import type { ModelSpec, RunRequest, ToolSpec } from "./types.js";

export const SERVICE_VERSION = "0.1.0";

export function handleHealth(res: ServerResponse): void {
  respondJson(res, 200, { ok: true, version: SERVICE_VERSION });
}

/** `/v1/run`: SSE round loop. See `executeRun` for the event contract. */
export async function handleRun(
  res: ServerResponse,
  body: string,
  token: string,
  signal: AbortSignal,
): Promise<void> {
  const request = validateRunRequest(body);
  console.log(
    `[hand] run start runId=${request.runId} model=${request.model.modelId}`,
  );
  await executeRun(res, request, token, signal);
}

function parseBody(body: string): Record<string, unknown> {
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

function validateRunRequest(body: string): RunRequest {
  const raw = parseBody(body);
  const model = validateModelSpec(raw.model);
  // the hand trusts Kotlin to send valid messages (Kotlin validates on
  // encode); only the envelope is checked here
  const messages = raw.messages;
  if (!Array.isArray(messages)) {
    failInvalid("messages must be an array");
  }
  const maxTokens = validatePositiveInt(raw.maxTokens, "maxTokens");
  const systemPrompt = raw.systemPrompt === undefined ? undefined : validateString(raw.systemPrompt, "systemPrompt");
  const runId = validateString(raw.runId, "runId");
  const maxRounds = validateNonNegativeInt(raw.maxRounds, "maxRounds");
  const maxRetries = validateNonNegativeInt(raw.maxRetries, "maxRetries");
  const streamIdleTimeoutMs =
    validateNonNegativeInt(raw.streamIdleTimeoutMs, "streamIdleTimeoutMs");
  // the tool set is not passed statically: the hand queries
  // `GET {toolListUrl}?runId=...` before every LLM request instead
  let toolListUrl: string | undefined;
  if (raw.toolListUrl !== undefined) {
    toolListUrl = validateString(raw.toolListUrl, "toolListUrl");
    if (!/^https?:\/\//.test(toolListUrl)) {
      failInvalid("toolListUrl must be an http(s) URL");
    }
  }
  let toolCallbackUrl: string | undefined;
  if (toolListUrl !== undefined) {
    toolCallbackUrl = validateString(raw.toolCallbackUrl, "toolCallbackUrl");
    if (!/^https?:\/\//.test(toolCallbackUrl)) {
      failInvalid("toolCallbackUrl must be an http(s) URL");
    }
  }
  return { model, messages, systemPrompt, maxTokens, runId, toolListUrl, toolCallbackUrl, maxRounds, maxRetries, streamIdleTimeoutMs };
}

function validateModelSpec(value: unknown): ModelSpec {
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

function validateString(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    failInvalid(`${field} must be a non-blank string`);
  }
  return value;
}

function validatePositiveInt(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value <= 0) {
    failInvalid(`${field} must be a positive integer`);
  }
  return value;
}

function validateNonNegativeInt(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0) {
    failInvalid(`${field} must be a non-negative integer`);
  }
  return value;
}
