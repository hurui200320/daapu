/**
 * The `/v1/run` round loop (spec §3.3): stream a round, classify its
 * terminal event, retry transient failures with backoff, execute tool calls
 * in parallel, and relay everything as named SSE events — exactly one of
 * `done`/`error` closes the run. The hand holds no state beyond this
 * function: everything it needs arrives in the request.
 *
 * This file is deliberately only the LOOP. Finish classification lives in
 * `classification.ts`, and the outbound brain calls (tool listing + tool
 * callbacks) in `toolTransport.ts`.
 */

import { uuidv7 } from "@earendil-works/pi-ai";
import type { AssistantMessage as PiAssistantMessage, Model as PiModel } from "@earendil-works/pi-ai";
import type { ServerResponse } from "node:http";
import { backoffDelayMs, sleepOrAbort } from "./backoff.js";
import { classifyTerminal, isTransientError } from "./classification.js";
import { assembleAssistantMessage, toPiContext } from "./convert.js";
import { buildModel, openStream, type TerminalOutcome } from "./piCall.js";
import { fetchTools, postToolCallback, type CallbackResult } from "./toolTransport.js";
import { writeSseComment, writeSseEvent, writeSseHead, type SseEventName } from "./sse.js";
import { fullInputTokens } from "./usage.js";
import { HandFailure, type ChatMessage, type ChatMessagePart, type RunRequest, type ToolSpec } from "./types.js";

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
  const maxRounds = request.maxRounds;
  const maxRetries = request.maxRetries;
  const idleTimeoutMs = request.streamIdleTimeoutMs;
  const effectiveMaxTokens = request.maxTokens;

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
        // the tool set is resolved fresh before EVERY LLM request (each
        // attempt, retries included): the brain's `GET {toolListUrl}` answers
        // with the in-flight run's provider advertisements, so a run always
        // sees the latest tools (MCP servers can change theirs at runtime)
        let roundTools: ToolSpec[] | undefined;
        if (request.toolListUrl !== undefined) {
          const toolsOutcome = await fetchTools(request.toolListUrl, request.runId, token, signal);
          if (toolsOutcome.kind === "abort") {
            return;
          }
          if (toolsOutcome.kind === "failure") {
            emit("error", { type: "tool_transport", message: toolsOutcome.message });
            outcome = "error:tool_transport";
            return;
          }
          roundTools = toolsOutcome.tools;
        }
        const roundOutcome = await streamOneRound(model, history, roundTools, request, emit, signal, idleTimeoutMs);
        if (roundOutcome.kind === "error") {
          if (roundOutcome.aborted && (signal.aborted || brainGone)) {
            return;
          }
          // an abort the client did not cause is the idle timeout firing; a
          // non-abort error event is retried exactly when it classifies as
          // transient — the two retry paths share one policy below
          if (roundOutcome.aborted || isTransientError(roundOutcome.message, request.model.contextWindow)) {
            const retry = await retryRound(
              emit,
              signal,
              attempt,
              maxRetries,
              roundOutcome.aborted
                ? "stream idle timeout, retrying"
                : (roundOutcome.message.errorMessage ?? "transient upstream failure"),
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
          if (classification.ok) {
            // unreachable: for the "error" outcome classifyTerminal always
            // answers `{ok:false}` (content_filter/overflow/upstream)
            return;
          }
          emit("error", classification.error);
          outcome = `error:${classification.error.type}`;
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
            if (usage !== undefined && (fullInputTokens(usage) > 0 || (usage.output ?? 0) > 0)) {
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
        const assembled = assembleAssistantMessage(normalizeToolCallIds(roundOutcome.message), request.model.modelId);
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
          const callbackOutcome = await executeToolCalls(assembled.message, history, request, token, signal, emit);
          if (callbackOutcome === "abort") {
            outcome = "aborted";
            return;
          }
          if (callbackOutcome === "transport_failure") {
            outcome = "error:tool_transport";
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
    maxTokens: request.maxTokens,
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
        return { kind: "done", message: event.message };
      } else if (event.type === "error") {
        return { kind: "error", message: event.error, aborted: event.reason === "aborted" };
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
  return message.content.some((block) => block.type === "text" && block.text.trim().length > 0);
}

type ToolCallsOutcome = "done" | "abort" | "transport_failure";

/** A round's tool-call part (the object `parts` filters down to). */
type ToolCallPart = Extract<ChatMessagePart, { type: "tool_call" }>;

/** A callback that answered with a tool result. */
type CallbackOk = Extract<CallbackResult, { kind: "ok" }>;

/**
 * Executes the round's tool calls in parallel: the hand fires every callback
 * POST of the round at once and reassembles the results in source order (the
 * same order the model emitted the calls), so the stored history and the
 * next round's request keep the call→result pairing strict providers expect.
 * The `tool_call` events all precede the `tool_result` events — the frontend
 * commits the round's assistant message on the first result, so it must see
 * the full call set first.
 *
 * The hand never retries a callback POST (a retry could duplicate a
 * side-effecting tool), and the callback applies no deadline of its own: the
 * brain enforces each tool's execution budget and always answers (a result,
 * an `isError` timeout, or a `fatal`); if the brain crashes mid-call, the
 * connection drop fails the fetch and the run ends with `tool_transport`.
 * A client disconnect aborts every in-flight POST via the shared signal; a
 * hang simply stalls until then, exactly like the serial loop.
 *
 * Parallelism has one semantic cost: every tool of the round has ALREADY
 * executed (and any side effects committed) by the time the results are
 * checked. A `fatal`/transport failure on ANY call fails the whole run with
 * `tool_transport` and discards every result — including the successes — so
 * a tool that answered fine still ran even though the round is thrown away.
 * (The serial loop stopped at the first failure, so later tools never ran.)
 */
async function executeToolCalls(
  assistantMessage: ChatMessage,
  history: ChatMessage[],
  request: RunRequest,
  token: string,
  signal: AbortSignal,
  emit: Emit,
): Promise<ToolCallsOutcome> {
  const callbackUrl = request.toolCallbackUrl;
  if (callbackUrl === undefined) {
    emit("error", { type: "internal", message: "tool calls received but no toolCallbackUrl" });
    return "transport_failure";
  }
  const isToolCall = (part: ChatMessagePart): part is ToolCallPart => part.type === "tool_call";
  const parts = assistantMessage.parts.filter(isToolCall);
  for (const part of parts) {
    if (!emit("tool_call", { id: part.id, name: part.tool, args: part.args })) {
      return "abort";
    }
  }
  // fire every callback concurrently and wait for all of them: the brain
  // always answers, so the wait is bounded by the callbacks themselves, and
  // `postToolCallback` resolves (never rejects) even on abort/transport
  // failure, so no promise is left unhandled
  const outcomes = await Promise.all(
    parts.map((part) =>
      postToolCallback(
        callbackUrl,
        token,
        { runId: request.runId, id: part.id, name: part.tool, args: part.args },
        signal,
      ),
    ),
  );
  // all callbacks have settled: check every outcome before assembling
  // anything, so a failure discards every result (the successes included).
  // `Promise.all` preserves length and order, so `outcomes[index]` is always
  // present; the guard only satisfies `noUncheckedIndexedAccess`
  const results: { part: ToolCallPart; result: CallbackOk }[] = [];
  for (const [index, part] of parts.entries()) {
    const result = outcomes[index];
    if (result === undefined) {
      emit("error", { type: "internal", message: "tool callback outcome missing" });
      return "transport_failure";
    }
    if (result.kind === "abort") {
      return "abort";
    }
    if (result.kind === "transport_failure") {
      emit("error", { type: "tool_transport", message: result.message });
      return "transport_failure";
    }
    results.push({ part, result });
  }
  for (const { part, result } of results) {
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
