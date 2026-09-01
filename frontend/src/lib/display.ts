import type { ChatAttachmentPart, ChatMessage, ChatMessagePart } from './types'

/**
 * Pure display-assembly helpers: they derive UI facts from the message
 * arrays but hold no state, so every rule below is unit-tested
 * (src/lib/*.test.ts). Extracted from ChatStore/MessageItem/MessageList to
 * keep those reactive hosts thin.
 */

/*
 * Mirror of the backend's data-URL validation — `dataUrlRegex` in
 * src/main/kotlin/info/skyblond/daapu/agent/chat/ImageAttachments.kt
 * (`^data:(image/[a-zA-Z0-9.+-]+);base64,(.+)$`, the image-MIME half shared
 * as `imageMimeTypeRegex`), plus its post-match handling: trim the URL,
 * strip whitespace from the base64 payload.
 * UPDATE BOTH PATTERNS TOGETHER. This copy is display-only (it prunes
 * non-image parts from the OPTIMISTIC user bubble; the request still
 * carries the raw URL and the backend performs the authoritative check),
 * but a divergence would render attachments locally that the backend then
 * rejects.
 */
const DATA_URL_RE = /^data:(image\/[a-zA-Z0-9.+-]+);base64,([\s\S]+)$/

/**
 * Stable per-message identity for collapsible open-state tracking: role +
 * the tool calls (name + args, in order; ids ignored — the display commit
 * has none) + the joined text parts. Content-based because the `done` reload
 * replaces every message object wholesale and a mid-run compaction shifts
 * positions: the same round must keep its signature wherever it lands, and
 * a different round must never inherit another's. The text join is
 * coalescing-invariant (the display commit's single text part vs. the stored
 * form's provider blocks join to the same string). Approximate identity:
 * identical content is indistinguishable by design (and visually too).
 *
 * Tool_result messages all share the constant `role:tool_result` signature:
 * their collapsibles key on the unique result id instead (see
 * [partOrdinalKey]), so a shared bucket cannot mix up their
 * toggles — and only a shared bucket survives the display's batching (one
 * display message per round's results, one stored message per result).
 */
export function roundSignature(m: ChatMessage): string {
  if (m.role !== 'assistant') return `role:${m.role}`
  const calls = m.parts
    .filter((p): p is Extract<ChatMessagePart, { type: 'tool_call' }> => p.type === 'tool_call')
    .map((c) => [c.tool, c.args])
  const text = m.parts
    .filter((p): p is Extract<ChatMessagePart, { type: 'text' }> => p.type === 'text')
    .map((p) => p.text)
    .join('')
  return `assistant:${JSON.stringify(calls)}:${text}`
}

/**
 * Stable identity of a part for open-state tracking: type + ordinal within
 * the type — except tool_result parts, which key on their result id. The
 * display commit coalesces reasoning/text while the stored form keeps the
 * provider's blocks, so a raw part index would lose track of a collapsible
 * across the done-reload. A tool result's id is stable across the reload
 * AND across the display's batching (a round's results share ONE display
 * tool_result message but are stored one message per result — no
 * message-level signature could reconcile that), so the id-keyed part
 * lives under the shared `role:tool_result` signature bucket and the
 * override follows the result wherever it lands.
 */
export function partOrdinalKey(parts: ChatMessagePart[], pi: number): string {
  const part = parts[pi]
  if (!part) return `missing:${pi}`
  if (part.type === 'tool_result') return `tool_result:${part.id}`
  const type = part.type
  let ordinal = 0
  for (let j = 0; j < pi; j++) {
    if (parts[j].type === type) ordinal++
  }
  return `${type}:${ordinal}`
}

/**
 * Vertical rhythm for the chat list: standalone messages sit 2rem apart, but
 * a tool chain (assistant tool calls → tool_result → next tool round) is
 * visually glued together — only the chain's first message keeps the full
 * gap, the rest are separated by the blocks' own 4px margins.
 */
export function messageSpacing(messages: ChatMessage[], i: number): string {
  if (i === 0) return ''
  const prev = messages[i - 1]
  const curr = messages[i]
  const chained =
    curr.role === 'tool_result' || (prev.role === 'tool_result' && curr.parts.some((p) => p.type === 'tool_call'))
  return chained ? '' : 'mt-8'
}

/**
 * Parse an image data URL into the backend-neutral attachment shape (used
 * for the optimistic user bubble AND the ELTM import's wire parts). Mirrors
 * the server-side regex: non-image MIME types and non-data URLs yield null.
 * The null means "skip" either way, but the validation story differs per
 * caller: the optimistic bubble simply drops the part (the chat-send request
 * still carries the raw data URL, where the backend performs the
 * authoritative validation), while the ELTM import sends the CONVERTED part,
 * validated by the route's attachment checks (see EltmRoute.kt).
 */
export function dataUrlToImagePart(dataUrl: string): ChatAttachmentPart | null {
  const match = DATA_URL_RE.exec(dataUrl.trim())
  if (!match) return null
  return {
    type: 'attachment',
    kind: 'image',
    mimeType: match[1],
    content: { type: 'base64', base64: match[2].replace(/\s/g, '') },
  }
}
