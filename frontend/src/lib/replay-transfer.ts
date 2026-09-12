import type { ChatMessage } from './types'

function isMessageLike(value: unknown): value is ChatMessage {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as ChatMessage).role === 'string' &&
    Array.isArray((value as ChatMessage).parts)
  )
}

/**
 * Parse a neutral-format chat messages file (the `.messages.json` shape
 * `GET /api/chats/{id}/chat` serves, e.g. the SillyTavern transformer's
 * output) into the replay's upload payload with a minimal shape check (a
 * non-empty array of message objects with `role` and `parts`): the server
 * owns the deep stored-chat validation (see `api.ts`
 * `startEltmReplay`), this only rejects obviously-wrong files before a
 * request is spent, with the file name in every error message.
 */
export async function parseChatMessagesFile(file: File): Promise<ChatMessage[]> {
  let parsed: unknown
  try {
    parsed = JSON.parse(await file.text())
  } catch {
    throw new Error(`"${file.name}" is not valid JSON`)
  }
  if (!Array.isArray(parsed) || parsed.length === 0 || !parsed.every(isMessageLike)) {
    throw new Error(
      `"${file.name}" is not a chat messages file (expected a non-empty array of {"role", "parts"} messages)`,
    )
  }
  return parsed
}

/** The replay's window knobs (server authority: `EltmReplayService.kt`). */
export interface ReplayKnobs {
  compactionRounds: number
  contextRounds: number
}

/**
 * Parse and validate the replay's window knobs, mirroring the server's
 * bounds (authority: `memory/eltm/EltmReplayService.kt`
 * `validateReplayKnobs` — the server re-validates and 400s). Returns the
 * parsed pair, or an error naming the rule (shown inline under the input).
 */
export function parseReplayKnobs(
  compactionRounds: string,
  contextRounds: string,
): { knobs?: ReplayKnobs; error?: string } {
  const cr = Number(compactionRounds)
  const ctx = Number(contextRounds)
  if (!Number.isInteger(cr) || cr < 2) {
    return { error: 'compactionRounds must be an integer >= 2 (every window drops that many rounds)' }
  }
  if (!Number.isInteger(ctx) || ctx < 1) {
    return { error: 'contextRounds must be an integer >= 1 (the compactor keeps that many reference rounds)' }
  }
  return { knobs: { compactionRounds: cr, contextRounds: ctx } }
}
