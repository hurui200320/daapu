import { parseChatExportFile } from './chat-transfer'
import type { ChatExport, ChatMessage } from './types'

function isMessageLike(value: unknown): value is ChatMessage {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as ChatMessage).role === 'string' &&
    Array.isArray((value as ChatMessage).parts)
  )
}

/**
 * Parse an exported-format chat file (the `{title, messages}` shape
 * `GET /api/chats/{id}/export` serves — the chat import's shape too) into
 * the replay's upload payload: the payload shape check is the chat import's
 * own parser (`parseChatExportFile` — one format, one parser, same error
 * messages), and this adds the replay-only extras (a non-empty array of
 * message objects with `role` and `parts`). The server owns the deep
 * stored-chat validation (see `api.ts` `startEltmReplay`), so the extras
 * only reject obviously-wrong files before a request is spent, with the
 * file name in every error message.
 */
export async function parseReplayChatFile(file: File): Promise<ChatExport> {
  const payload = await parseChatExportFile(file)
  if (!payload.messages.length || !payload.messages.every(isMessageLike)) {
    throw new Error(
      `"${file.name}" does not carry replayable messages (expected a non-empty array of {"role", "parts"} messages)`,
    )
  }
  return payload
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
