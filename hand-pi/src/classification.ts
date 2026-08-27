/**
 * Finish classification for the `/v1/run` round loop (spec §3.3), kept
 * separate from the loop itself so it can be unit-tested without pi-ai
 * streaming or SSE.
 *
 * daapu's classification: a `length` finish means `context_exhausted` when
 * the prompt overflows the window minus the output budget,
 * `output_budget_exhausted` otherwise. A gateway-side rejection (HTTP
 * 400/413 error body) is the same "input overflows the window" signal as a
 * near-window length finish, so it classifies as `context_exhausted` via
 * pi-ai's `isContextOverflow`.
 */

import { isContextOverflow } from "@earendil-works/pi-ai";
import type { AssistantMessage as PiAssistantMessage } from "@earendil-works/pi-ai";
import type { HandError } from "./types.js";
import { fullInputTokens } from "./usage.js";

export type FinishClassification = { ok: true; finishReason: string } | { ok: false; error: HandError };

const CONTENT_FILTER_MARKER = "Provider finish_reason: content_filter";

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
  const inputTokens = fullInputTokens(usage);
  if (inputTokens > contextWindow - effectiveMaxTokens) {
    return {
      ok: false,
      error: {
        type: "context_exhausted",
        message: `input ${inputTokens} tokens exceeds context window ${contextWindow} minus output budget ${effectiveMaxTokens}`,
      },
    };
  }
  return {
    ok: false,
    error: { type: "output_budget_exhausted", message: "output hit the token budget" },
  };
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
