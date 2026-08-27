/**
 * Shared pi-ai usage arithmetic. `fullInputTokens` is the FULL prompt size
 * (`prompt_tokens`), never pi-ai's cache-subtracted input count — the Kotlin
 * classifier and the proactive compaction trigger depend on it.
 */

import type { Usage as PiUsage } from "@earendil-works/pi-ai";

/** The zero usage pi-ai initializes assistant messages with (replays reuse it). */
export const ZERO_USAGE: PiUsage = {
  input: 0,
  output: 0,
  cacheRead: 0,
  cacheWrite: 0,
  totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

/** The full prompt size pi-ai's cache-subtracted usage represents. */
export function fullInputTokens(usage: PiUsage): number {
  return (usage.input ?? 0) + (usage.cacheRead ?? 0) + (usage.cacheWrite ?? 0);
}
