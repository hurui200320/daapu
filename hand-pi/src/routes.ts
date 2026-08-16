import type { ServerResponse } from "node:http";
import { assembleAssistantMessage, failInvalid, isRecord, toPiContext } from "./convert.js";
import { respondJson } from "./http.js";
import { buildModel, driveToCompletion } from "./piCall.js";
import { classifyTerminal, executeRun } from "./run.js";
import type { ChatMessage, CompleteRequest, CompleteResponse, ModelSpec, RunRequest, ToolSpec } from "./types.js";

export const SERVICE_VERSION = "0.1.0";

export function handleHealth(res: ServerResponse): void {
  respondJson(res, 200, { ok: true, version: SERVICE_VERSION });
}

/**
 * `/v1/complete`: single JSON response, non-streaming. Serves the extractor,
 * the compactor summary, and each merger round. Shares `/run`'s finish
 * classification — a `length` finish classifies as `context_exhausted` /
 * `output_budget_exhausted`, never as a clean `stop`.
 */
export async function handleComplete(
  res: ServerResponse,
  body: string,
  signal: AbortSignal,
): Promise<void> {
  const request = validateCompleteRequest(body);
  const model = buildModel(request.model);
  const context = toPiContext(request.messages, request.systemPrompt, request.model);
  const effectiveMaxTokens = request.maxTokens ?? request.model.maxOutputTokens;
  const terminal = await driveToCompletion(model, context, request.tools, {
    apiKey: request.model.apiKey,
    maxTokens: effectiveMaxTokens,
    reasoningEffort: request.model.reasoningEffort,
    signal,
  });
  if (terminal.outcome === "error" && terminal.aborted) {
    res.destroy();
    return;
  }
  const classification = classifyTerminal(
    terminal.outcome === "done" ? "done" : "error",
    terminal.message,
    request.model.contextWindow,
    effectiveMaxTokens,
  );
  if (classification.ok) {
    const assembled = assembleAssistantMessage(terminal.message, request.model.modelId);
    const response: CompleteResponse = {
      ok: true,
      message: assembled.message,
      finishReason: assembled.finishReason,
    };
    logComplete(request.model.modelId, "ok", assembled.finishReason, assembled.message.meta);
    respondJson(res, 200, response);
  } else {
    logComplete(request.model.modelId, classification.error.type, undefined, undefined);
    const response: CompleteResponse = { ok: false, error: classification.error };
    respondJson(res, 200, response);
  }
}

function validateCompleteRequest(body: string): CompleteRequest {
  return validateCompleteBody(parseBody(body));
}

function validateCompleteBody(raw: Record<string, unknown>): CompleteRequest {
  const model = validateModelSpec(raw.model);
  // the hand trusts Kotlin to send valid messages (Kotlin validates on
  // encode); only the envelope is checked here
  const messages = raw.messages;
  if (!Array.isArray(messages)) {
    failInvalid("messages must be an array");
  }
  const tools = raw.tools === undefined ? undefined : validateTools(raw.tools);
  const maxTokens = raw.maxTokens === undefined ? undefined : validatePositiveInt(raw.maxTokens, "maxTokens");
  const systemPrompt = raw.systemPrompt === undefined ? undefined : validateString(raw.systemPrompt, "systemPrompt");
  return { model, messages: messages as ChatMessage[], systemPrompt, tools, maxTokens };
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

/** `/v1/run`: SSE round loop. See `executeRun` for the event contract. */
export async function handleRun(
  res: ServerResponse,
  body: string,
  token: string,
  signal: AbortSignal,
): Promise<void> {
  const request = validateRunRequest(body);
  console.log(
    `[hand] run start runId=${request.runId} chatId=${request.chatId} model=${request.model.modelId}`,
  );
  await executeRun(res, request, token, signal);
}

function validateRunRequest(body: string): RunRequest {
  const raw = parseBody(body);
  const base = validateCompleteBody(raw);
  const runId = validateString(raw.runId, "runId");
  const chatId = validateString(raw.chatId, "chatId");
  const maxRounds =
    raw.maxRounds === undefined ? undefined : validateNonNegativeInt(raw.maxRounds, "maxRounds");
  const maxRetries =
    raw.maxRetries === undefined ? undefined : validateNonNegativeInt(raw.maxRetries, "maxRetries");
  const streamIdleTimeoutMs =
    raw.streamIdleTimeoutMs === undefined
      ? undefined
      : validateNonNegativeInt(raw.streamIdleTimeoutMs, "streamIdleTimeoutMs");
  const callbackTimeoutMs =
    raw.callbackTimeoutMs === undefined
      ? undefined
      : validateNonNegativeInt(raw.callbackTimeoutMs, "callbackTimeoutMs");
  let toolCallbackUrl: string | undefined;
  if (base.tools !== undefined && base.tools.length > 0) {
    toolCallbackUrl = validateString(raw.toolCallbackUrl, "toolCallbackUrl");
    if (!/^https?:\/\//.test(toolCallbackUrl)) {
      failInvalid("toolCallbackUrl must be an http(s) URL");
    }
  }
  return { ...base, runId, chatId, toolCallbackUrl, maxRounds, maxRetries, streamIdleTimeoutMs, callbackTimeoutMs };
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

function validateTools(value: unknown): ToolSpec[] {
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
    // required: every tool advertises its execution budget (0 = no timeout)
    const timeoutSeconds = validateNonNegativeInt(rawTool.timeoutSeconds, `tools[${index}].timeoutSeconds`);
    return { name, description, schema: rawTool.schema, timeoutSeconds };
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

function logComplete(
  modelId: string,
  outcome: string,
  finishReason: string | undefined,
  meta: { inputTokens?: number; outputTokens?: number } | undefined,
): void {
  const fields = [`model=${modelId}`, `outcome=${outcome}`];
  if (finishReason !== undefined) {
    fields.push(`finish=${finishReason}`);
  }
  if (meta?.inputTokens !== undefined) {
    fields.push(`in=${meta.inputTokens}`);
  }
  if (meta?.outputTokens !== undefined) {
    fields.push(`out=${meta.outputTokens}`);
  }
  console.log(`[hand] complete ${fields.join(" ")}`);
}
