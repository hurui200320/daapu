/**
 * Shared finish classification plus the `/v1/run` round loop (spec §3.3).
 *
 * daapu's classification: a `length` finish means `context_exhausted` when
 * the prompt overflows the window minus the output budget,
 * `output_budget_exhausted` otherwise. A gateway-side rejection (HTTP
 * 400/413 error body) is the same "input overflows the window" signal as a
 * near-window length finish, so it classifies as `context_exhausted` via
 * pi-ai's `isContextOverflow`.
 */

import { isContextOverflow, uuidv7 } from "@earendil-works/pi-ai";
import type { AssistantMessage as PiAssistantMessage, Model as PiModel } from "@earendil-works/pi-ai";
import type { ServerResponse } from "node:http";
import {
  assembleAssistantMessage,
  isRecord,
  toPiContext,
} from "./convert.js";
import { buildModel, openStream, type TerminalOutcome } from "./piCall.js";
import { writeSseComment, writeSseEvent, writeSseHead, type SseEventName } from "./sse.js";
import {
  HandFailure,
  type ChatMessage,
  type ContentPart,
  type HandError,
  type RunRequest,
  type ToolSpec,
} from "./types.js";

export type FinishClassification =
  | { ok: true; finishReason: string }
  | { ok: false; error: HandError };

const CONTENT_FILTER_MARKER = "Provider finish_reason: content_filter";

const DEFAULT_MAX_ROUNDS = 64;
const DEFAULT_CALLBACK_TIMEOUT_MS = 120_000;
const DEFAULT_STREAM_IDLE_TIMEOUT_MS = 300_000;

/**
 * Classifies a pi-ai terminal message. `outcome` is `done`/`error` matching
 * the terminal event that carried the message.
 */
export function classifyTerminal(
  outcome: "done" | "error",
  message: PiAssistantMessage,
  contextWindow: number,
  effectiveMaxTokens: number,
): FinishClassification {
  if (outcome === "done") {
    switch (message.stopReason) {
      case "stop":
        return { ok: true, finishReason: "stop" };
      case "toolUse":
        return { ok: true, finishReason: "tool_calls" };
      // Deferred is only produced by streamSimple's deferred mode, which the
      // hand never requests; a provider emitting it anyway ends a round.
      case "deferred":
        return { ok: true, finishReason: "stop" };
      case "length":
        return classifyLength(message, contextWindow, effectiveMaxTokens);
      default:
        return {
          ok: false,
          error: {
            type: "internal",
            message: `unexpected pi-ai stop reason '${message.stopReason}'`,
          },
        };
    }
  }
  const errorMessage = message.errorMessage ?? "";
  if (errorMessage.includes(CONTENT_FILTER_MARKER)) {
    return { ok: false, error: { type: "content_filter", message: errorMessage } };
  }
  if (isContextOverflow(message, contextWindow)) {
    return { ok: false, error: { type: "context_exhausted", message: errorMessage } };
  }
  return { ok: false, error: { type: "upstream", message: errorMessage } };
}

function classifyLength(
  message: PiAssistantMessage,
  contextWindow: number,
  effectiveMaxTokens: number,
): FinishClassification {
  const usage = message.usage;
  if (usage === undefined) {
    return {
      ok: false,
      error: { type: "output_budget_exhausted", message: "length finish without usage data" },
    };
  }
  const inputTokens = fullInput(usage);
  if (inputTokens > contextWindow - effectiveMaxTokens) {
    return {
      ok: false,
      error: {
        type: "context_exhausted",
        message:
          `input ${inputTokens} tokens exceeds context window ${contextWindow} minus output budget ${effectiveMaxTokens}`,
      },
    };
  }
  return {
    ok: false,
    error: { type: "output_budget_exhausted", message: "output hit the token budget" },
  };
}

function fullInput(usage: NonNullable<PiAssistantMessage["usage"]>): number {
  return (usage.input ?? 0) + (usage.cacheRead ?? 0) + (usage.cacheWrite ?? 0);
}

/**
 * The transient set (spec §3.3.8): 5xx responses, mid-stream error chunks,
 * network failures, and truncated streams. Terminal: content_filter,
 * context overflow, and non-retryable 4xx responses.
 */
export function isTransientError(message: PiAssistantMessage, contextWindow: number): boolean {
  const text = message.errorMessage ?? "";
  if (text.includes(CONTENT_FILTER_MARKER)) {
    return false;
  }
  if (isContextOverflow(message, contextWindow)) {
    return false;
  }
  if (/^4\d\d(?:\s|:|$)/.test(text)) {
    return false;
  }
  if (text.includes("No API key for provider")) {
    return false;
  }
  return true;
}

/** Exponential backoff: `100ms << attempt`, capped at 6.4s. */
export function backoffDelayMs(attempt: number): number {
  return Math.min(100 * 2 ** (attempt - 1), 6400);
}

type Emit = (event: SseEventName, payload: unknown) => boolean;

/**
 * `/v1/run`: the chat round loop. Writes named SSE events; exactly one of
 * `done`/`error` closes the run. The hand holds no state beyond this
 * function: everything it needs arrives in the request.
 */
export async function executeRun(
  res: ServerResponse,
  request: RunRequest,
  token: string,
  signal: AbortSignal,
): Promise<void> {
  writeSseHead(res);
  writeSseComment(res, "connected");

  const model = buildModel(request.model);
  const history: ChatMessage[] = [...request.messages];
  const tools = request.tools;
  const maxRounds = request.maxRounds ?? DEFAULT_MAX_ROUNDS;
  const maxRetries = request.maxRetries ?? 0;
  const callbackTimeoutMs = request.callbackTimeoutMs ?? DEFAULT_CALLBACK_TIMEOUT_MS;
  const idleTimeoutMs = request.streamIdleTimeoutMs ?? DEFAULT_STREAM_IDLE_TIMEOUT_MS;
  const effectiveMaxTokens = request.maxTokens ?? request.model.maxOutputTokens;

  let brainGone = false;
  const emit: Emit = (event, payload) => {
    if (brainGone) {
      return false;
    }
    try {
      writeSseEvent(res, event, payload);
      return true;
    } catch {
      brainGone = true;
      return false;
    }
  };

  let round = 0;
  let outcome = "aborted";
  let totalInputTokens = 0;
  let totalOutputTokens = 0;

  try {
    rounds: while (true) {
      round++;
      let attempt = 0;
      while (true) {
        attempt++;
        const roundOutcome = await streamOneRound(
          model,
          history,
          tools,
          request,
          emit,
          signal,
          idleTimeoutMs,
        );
        if (roundOutcome.outcome === "error") {
          if (roundOutcome.aborted) {
            if (signal.aborted || brainGone) {
              return;
            }
            const retry = await retryRound(
              emit,
              signal,
              attempt,
              maxRetries,
              "stream idle timeout, retrying",
            );
            if (retry === "gave_up") {
              outcome = "error:upstream";
              return;
            }
            if (retry === "aborted") {
              return;
            }
            continue;
          }
          if (isTransientError(roundOutcome.message, request.model.contextWindow)) {
            const retry = await retryRound(
              emit,
              signal,
              attempt,
              maxRetries,
              roundOutcome.message.errorMessage ?? "transient upstream failure",
            );
            if (retry === "gave_up") {
              outcome = "error:upstream";
              return;
            }
            if (retry === "aborted") {
              return;
            }
            continue;
          }
          const classification = classifyTerminal(
            "error",
            roundOutcome.message,
            request.model.contextWindow,
            effectiveMaxTokens,
          );
          if (!classification.ok) {
            emit("error", classification.error);
            outcome = `error:${classification.error.type}`;
          }
          return;
        }
        const classification = classifyTerminal(
          "done",
          roundOutcome.message,
          request.model.contextWindow,
          effectiveMaxTokens,
        );
        if (!classification.ok) {
          // Only `length` reaches here: preserve the partial round for the
          // frontend (spec §3.3 step 3) before failing the run.
          if (roundOutcome.message.stopReason === "length") {
            const usage = roundOutcome.message.usage;
            // a message without provider-reported usage cannot be assembled
            // (daapu requires usage on every accepted message); the partial
            // is skipped so the classified error survives instead of being
            // replaced by an assembly failure
            if (usage !== undefined && (fullInput(usage) > 0 || (usage.output ?? 0) > 0)) {
              const partial = assembleAssistantMessage(
                normalizeToolCallIds(roundOutcome.message),
                request.model.modelId,
              );
              if (!emit("assistant_message", { message: partial.message })) {
                return;
              }
            }
          }
          emit("error", classification.error);
          outcome = `error:${classification.error.type}`;
          return;
        }
        // a stop with neither text nor tool calls is a deliberate provider
        // answer, not a transient hiccup: retrying the identical prompt
        // would spin forever, so the run fails (like content_filter)
        if (classification.finishReason === "stop" && !hasText(roundOutcome.message)) {
          emit("error", {
            type: "empty_response",
            message: "assistant finished with neither text nor tool calls",
          });
          outcome = "error:empty_response";
          return;
        }
        const assembled = assembleAssistantMessage(
          normalizeToolCallIds(roundOutcome.message),
          request.model.modelId,
        );
        history.push(assembled.message);
        totalInputTokens += assembled.message.meta?.inputTokens ?? 0;
        totalOutputTokens += assembled.message.meta?.outputTokens ?? 0;
        if (!emit("assistant_message", { message: assembled.message })) {
          return;
        }
        if (classification.finishReason === "tool_calls") {
          if (maxRounds > 0 && round >= maxRounds) {
            emit("error", {
              type: "round_limit",
              message: `maxRounds (${maxRounds}) reached at round ${round}`,
            });
            outcome = "error:round_limit";
            return;
          }
          const callbackOutcome = await executeToolCalls(
            assembled.message,
            history,
            request,
            token,
            signal,
            callbackTimeoutMs,
            emit,
          );
          if (callbackOutcome === "abort") {
            return;
          }
          if (callbackOutcome === "transport_failure") {
            return;
          }
          continue rounds;
        }
        emit("done", { finishReason: "stop" });
        outcome = "done";
        return;
      }
    }
  } catch (error) {
    if (error instanceof HandFailure) {
      emit("error", error.handError);
      outcome = `error:${error.handError.type}`;
    } else {
      const message = error instanceof Error ? error.message : String(error);
      console.error(`[hand] internal error during run ${request.runId}: ${message}`);
      emit("error", { type: "internal", message });
      outcome = "error:internal";
    }
  } finally {
    console.log(
      `[hand] run end runId=${request.runId} model=${request.model.modelId} rounds=${round} ` +
        `outcome=${outcome} in=${totalInputTokens} out=${totalOutputTokens}`,
    );
    if (!res.writableEnded) {
      res.end();
    }
  }
}

type RetryOutcome = "continue" | "gave_up" | "aborted";

async function retryRound(
  emit: Emit,
  signal: AbortSignal,
  attempt: number,
  maxRetries: number,
  message: string,
): Promise<RetryOutcome> {
  if (maxRetries > 0 && attempt >= maxRetries) {
    emit("error", {
      type: "upstream",
      message: `maxRetries (${maxRetries}) exhausted: ${message}`,
    });
    return "gave_up";
  }
  const delayMs = backoffDelayMs(attempt);
  if (!emit("retry", { attempt, delayMs, message })) {
    return "aborted";
  }
  return (await sleepOrAbort(delayMs, signal)) ? "aborted" : "continue";
}

/** Streams one round, relaying deltas and enforcing the stream idle timeout. */
async function streamOneRound(
  model: PiModel<"openai-completions">,
  history: ChatMessage[],
  tools: ToolSpec[] | undefined,
  request: RunRequest,
  emit: Emit,
  signal: AbortSignal,
  idleTimeoutMs: number,
): Promise<TerminalOutcome> {
  const context = toPiContext(history, request.systemPrompt, request.model);
  const idleController = new AbortController();
  let idleTimer: NodeJS.Timeout | undefined;
  const resetIdle = () => {
    if (idleTimeoutMs <= 0) {
      return;
    }
    if (idleTimer !== undefined) {
      clearTimeout(idleTimer);
    }
    idleTimer = setTimeout(() => idleController.abort(), idleTimeoutMs);
  };
  resetIdle();
  const events = openStream(model, context, tools, {
    apiKey: request.model.apiKey,
    maxTokens: request.maxTokens ?? request.model.maxOutputTokens,
    reasoningEffort: request.model.reasoningEffort,
    signal: AbortSignal.any([signal, idleController.signal]),
  });
  try {
    for await (const event of events) {
      resetIdle();
      if (event.type === "text_delta") {
        emit("text_delta", { text: event.delta });
      } else if (event.type === "thinking_delta") {
        emit("reasoning_delta", { text: event.delta });
      } else if (event.type === "done") {
        return { outcome: "done", message: event.message };
      } else if (event.type === "error") {
        return { outcome: "error", message: event.error, aborted: event.reason === "aborted" };
      }
    }
    throw new Error("pi-ai stream ended without a terminal event");
  } finally {
    if (idleTimer !== undefined) {
      clearTimeout(idleTimer);
    }
  }
}

/**
 * Normalizes blank tool-call ids: daapu's codec requires non-blank unique
 * ids, and the callback contract needs a stable id per call.
 */
function normalizeToolCallIds(message: PiAssistantMessage): PiAssistantMessage {
  let changed = false;
  const content = message.content.map((block) => {
    if (block.type === "toolCall" && (block.id === undefined || block.id.length === 0)) {
      changed = true;
      return { ...block, id: uuidv7() };
    }
    return block;
  });
  return changed ? { ...message, content } : message;
}

function hasText(message: PiAssistantMessage): boolean {
  return message.content.some(
    (block) => block.type === "text" && block.text.trim().length > 0,
  );
}

type ToolCallsOutcome = "done" | "abort" | "transport_failure";

/**
 * Executes the round's tool calls sequentially, in source order — the hand
 * never fires callbacks concurrently, and never retries a callback POST (a
 * retry could duplicate a side-effecting tool).
 */
async function executeToolCalls(
  assistantMessage: ChatMessage,
  history: ChatMessage[],
  request: RunRequest,
  token: string,
  signal: AbortSignal,
  callbackTimeoutMs: number,
  emit: Emit,
): Promise<ToolCallsOutcome> {
  const callbackUrl = request.toolCallbackUrl;
  if (callbackUrl === undefined) {
    emit("error", { type: "internal", message: "tool calls received but no toolCallbackUrl" });
    return "transport_failure";
  }
  for (const part of assistantMessage.parts) {
    if (part.type !== "tool_call") {
      continue;
    }
    if (!emit("tool_call", { id: part.id, name: part.tool, args: part.args })) {
      return "abort";
    }
    const result = await postToolCallback(
      callbackUrl,
      token,
      {
        runId: request.runId,
        chatId: request.chatId,
        id: part.id,
        name: part.tool,
        args: part.args,
      },
      signal,
      callbackTimeoutMs,
    );
    if (result.kind === "abort") {
      return "abort";
    }
    if (result.kind === "transport_failure") {
      emit("error", { type: "tool_transport", message: result.message });
      return "transport_failure";
    }
    history.push({
      role: "tool_result",
      parts: [
        {
          type: "tool_result",
          id: part.id,
          tool: part.tool,
          parts: result.parts,
          isError: result.isError,
        },
      ],
    });
    if (!emit("tool_result", { id: part.id, name: part.tool, parts: result.parts, isError: result.isError })) {
      return "abort";
    }
  }
  return "done";
}

type CallbackResult =
  | { kind: "ok"; parts: ContentPart[]; isError: boolean }
  | { kind: "transport_failure"; message: string }
  | { kind: "abort" };

async function postToolCallback(
  url: string,
  token: string,
  payload: unknown,
  signal: AbortSignal,
  timeoutMs: number,
): Promise<CallbackResult> {
  let response: Response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json", "x-daapu-token": token },
      body: JSON.stringify(payload),
      signal: AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]),
    });
  } catch (error) {
    if (signal.aborted) {
      return { kind: "abort" };
    }
    return {
      kind: "transport_failure",
      message: `tool callback failed: ${error instanceof Error ? error.message : String(error)}`,
    };
  }
  if (response.status !== 200) {
    return { kind: "transport_failure", message: `tool callback returned HTTP ${response.status}` };
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    return { kind: "transport_failure", message: "tool callback returned a non-JSON body" };
  }
  if (!isRecord(body)) {
    return { kind: "transport_failure", message: "tool callback returned a non-object body" };
  }
  if (body.fatal !== undefined) {
    const fatal = body.fatal;
    const message =
      isRecord(fatal) && typeof fatal.message === "string"
        ? fatal.message
        : "tool callback reported a fatal error";
    return { kind: "transport_failure", message };
  }
  const parts = body.parts;
  if (!Array.isArray(parts)) {
    return { kind: "transport_failure", message: "tool callback returned invalid parts" };
  }
  return {
    kind: "ok",
    parts: parts as ContentPart[],
    isError: body.isError === true,
  };
}

/** Sleeps until the delay elapses (false) or the signal aborts (true). */
export function sleepOrAbort(ms: number, signal: AbortSignal): Promise<boolean> {
  if (signal.aborted) {
    return Promise.resolve(true);
  }
  return new Promise((resolve) => {
    const cleanup = () => {
      clearTimeout(timer);
      signal.removeEventListener("abort", onAbort);
    };
    const timer = setTimeout(() => {
      cleanup();
      resolve(false);
    }, ms);
    const onAbort = () => {
      cleanup();
      resolve(true);
    };
    signal.addEventListener("abort", onAbort);
  });
}
