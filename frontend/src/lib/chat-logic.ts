/**
 * Pure decision logic lifted from ChatStore (chat-store.svelte.ts) so the
 * reactive host stays thin and the rules stay unit-testable without the
 * Svelte runtime (chat-logic.test.ts) — the same split as display.ts and
 * stream-session.ts.
 */
import type {
  ChatInfo,
  ChatListPage,
  ChatMessage,
  ChatMessagePart,
  ChatToolResultPart,
  ModelInfo,
  Persona,
} from './types'
import { DEFAULT_PERSONA_ID } from './types'

/**
 * The persona id the next send will carry: the transient picker override,
 * else the chat's recorded persona. A persona that no longer exists in the
 * loaded catalog (deleted here or in another tab) falls back to the other
 * source, then to the code default — the backend keeps stale records and
 * would 400 a stale id. An unloaded catalog (empty list) is still trusted:
 * a loaded catalog is never empty (the code default always leads it), and
 * the record was fetched together with the chats.
 */
export function effectivePersonaId(
  personas: Persona[],
  override: number | null,
  recordedId: number | undefined,
): number {
  const exists = (id: number) => personas.length === 0 || personas.some((p) => p.id === id)
  if (override != null && exists(override)) return override
  if (recordedId != null && exists(recordedId)) return recordedId
  return DEFAULT_PERSONA_ID
}

/**
 * Latest-round usage: scan backwards for the last assistant message that
 * carries token usage (each round's total = full prompt + output of that
 * round, the best proxy for current context occupancy). The context window
 * is always the currently selected model's, since the next message will be
 * processed by it. Missing either side (no usage data, unknown model —
 * the contextLength itself is always present) hides the indicator.
 */
export function computeUsage(
  messages: ChatMessage[],
  models: ModelInfo[],
  selectedModel: string,
): { used: number | null; context: number | null } {
  for (let i = messages.length - 1; i >= 0; i--) {
    const msg = messages[i]
    const used = msg.role === 'assistant' ? msg.meta?.totalTokens : null
    if (used == null) continue
    const model = models.find((m) => m.id === selectedModel)
    return { used, context: model?.contextLength ?? null }
  }
  return { used: null, context: null }
}

/** The live round's uncommitted streaming buffers (display-only state). */
export interface LiveRound {
  reasoning: string
  text: string
  toolCalls: { name: string; args: Record<string, unknown> }[]
}

/**
 * The round's assistant-message parts in display-commit order (reasoning,
 * text, tool calls — the calls carry a blank id: the SSE event has none,
 * and the `done` reload replaces the display commit with the stored form).
 * Null when the buffers hold nothing to commit.
 */
export function commitRoundParts(live: LiveRound): ChatMessagePart[] | null {
  const parts: ChatMessagePart[] = []
  if (live.reasoning) parts.push({ type: 'reasoning', content: live.reasoning })
  if (live.text) parts.push({ type: 'text', text: live.text })
  for (const call of live.toolCalls) {
    parts.push({ type: 'tool_call', id: '', tool: call.name, args: call.args })
  }
  return parts.length > 0 ? parts : null
}

/**
 * One tool result of the round arrived — the display-history branching that
 * used to be ChatStore's onToolResult verb:
 *
 * - the FIRST result of a batch commits the round's assistant message
 *   (reasoning + text + tool calls, from [live]) before attaching — the next
 *   round must stream as its own message, or its reasoning would append to
 *   this round's. The backend emits one event per result of a round
 *   (parallel tool calls => several). `committedRound` is true, so the
 *   caller wipes the buffers.
 * - 2nd..Nth results of the same batch (buffers already empty, history ends
 *   on the just-committed tool_result message) extend that message instead
 *   of creating an empty pair, mirroring the stored format — the caller must
 *   NOT wipe the buffers again (`committedRound: false`).
 *
 * Pure: returns the new array, never mutates the input.
 */
export function applyToolResult(
  messages: ChatMessage[],
  live: LiveRound,
  resultPart: ChatToolResultPart,
): { messages: ChatMessage[]; committedRound: boolean } {
  const last = messages[messages.length - 1]
  if (live.toolCalls.length === 0 && last?.role === 'tool_result') {
    return {
      messages: [...messages.slice(0, -1), { ...last, parts: [...last.parts, resultPart] }],
      committedRound: false,
    }
  }
  const parts = commitRoundParts(live)
  const withRound: ChatMessage[] = parts ? [...messages, { role: 'assistant', parts }] : [...messages]
  return { messages: [...withRound, { role: 'tool_result', parts: [resultPart] }], committedRound: true }
}

/**
 * Shared wording of a run failure: the chat view's banner and the
 * off-route fallback toast (ChatStore.send's finally) must stay in lockstep.
 */
export function runFailureText(error: string): string {
  return 'run failed: ' + error
}

/**
 * Fold one fetched page of the chat list into the known list — the resync's
 * reconciliation rule (ChatStore.resyncChats fetches only the FIRST page, so
 * the background tick stays one request regardless of chat count):
 *
 * - the fetched page is authoritative for its span of the newest-first id
 *   order: new chats, fresh titles/personas and deletions all land;
 * - the known tail OLDER than the page's boundary (its last chat id — the
 *   same `id < cursor` comparison the backend's keyset query uses) is kept
 *   as-is, so chats beyond the fetched page stay listed instead of
 *   vanishing on the next tick. That tail is best-effort: renames and
 *   deletes deeper than the newest page surface only through a full walk
 *   (initial load, export-all — see `api.ts` `listChats`), the same
 *   visibility the pre-pagination capped endpoint had.
 *
 * A page without `nextCursor` IS the complete list and replaces wholesale.
 * Pure: returns the new array, never mutates the inputs.
 */
export function mergeChatPage(known: ChatInfo[], page: ChatListPage): ChatInfo[] {
  // an empty page means the server holds no chats; an exhausted page (no
  // nextCursor) IS the whole list — either way the page replaces wholesale
  if (page.chats.length === 0 || page.nextCursor === undefined) return page.chats
  // every page id is >= its boundary (DESC order), so known entries at or
  // above the boundary are either in the page (the fresh copy wins) or were
  // deleted server-side — the tail below the boundary cannot duplicate one
  const boundary = page.chats[page.chats.length - 1].id
  return [...page.chats, ...known.filter((c) => c.id < boundary)]
}
