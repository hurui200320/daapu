/**
 * Loose mirror of the framework-neutral chat format the backend
 * serves (see agent/chat/ChatMessage.kt + the golden-format tests in
 * ChatCodecTest). The `type`/`role` discriminators are our own short
 * lowercase names — no framework type strings cross the API.
 */

type Role = 'user' | 'assistant' | 'tool_result'

interface ChatMessageMeta {
  inputTokens?: number
  outputTokens?: number
  totalTokens?: number
  modelId?: string
}

export interface ChatMessage {
  role: Role
  parts: ChatMessagePart[]
  /**
   * User messages only: when the message was sent (UTC ISO instant), the
   * source of the per-request `<meta>` time anchors. Not rendered.
   */
  createdAt?: string
  meta?: ChatMessageMeta
  finishReason?: string
}

export type ChatMessagePart = TextPart | ReasoningPart | ToolCallPart | ChatToolResultPart | ChatAttachmentPart

export interface TextPart {
  type: 'text'
  text: string
}

interface ReasoningPart {
  type: 'reasoning'
  content: string
}

interface ToolCallPart {
  type: 'tool_call'
  /** required by the format: a blank id would brick the chat on re-send */
  id: string
  tool: string
  args: Record<string, unknown>
}

export interface ChatToolResultPart {
  type: 'tool_result'
  /** required by the format: the id of the tool_call this result answers */
  id: string
  tool: string
  parts: ChatContentPart[]
  isError?: boolean
}

/** Text and attachments may also appear nested inside a tool_result. */
export type ChatContentPart = TextPart | ChatAttachmentPart

type AttachmentKind = 'image' | 'video' | 'audio' | 'file'

export interface ChatAttachmentPart {
  type: 'attachment'
  kind: AttachmentKind
  content: AttachmentContent
  mimeType: string
}

// URL attachment content is deliberately not supported (blocked at the
// backend boundary) until a real use case exists.
type AttachmentContent = { type: 'base64'; base64: string }

// The budgets are always present: the backend's LLM catalog validates both
// > 0 at boot (agent/model/LLM.kt), so /api/models never serves nulls here.
export interface ModelInfo {
  id: string
  vision: boolean
  contextLength: number
  maxOutputTokens: number
}

// ---- ELTM browse views (`GET /api/eltm`, read-only) ----

export interface EltmEntityDto {
  id: number
  canonicalName: string
  category: string
}

export interface EltmRelationshipDto {
  id: number
  srcId: number
  dstId: number
  verb: string
  valid: boolean
}

export interface EltmNoteDto {
  id: number
  entityId: number | null
  relationshipId: number | null
  /** the LLM-resolved absolute date of the event, `YYYY-MM-DD` */
  eventDate: string
  note: string
  createdAt: string
}

export interface EntityViewDto {
  entity: EltmEntityDto
  noteCount: number
  relationshipCount: number
  latestNote: EltmNoteDto | null
  /** current-state key-value facts (e.g. model, realname), keys sorted */
  attributes: Record<string, string>
}

export interface RelationshipViewDto {
  relationship: EltmRelationshipDto
  srcName: string
  dstName: string
  noteCount: number
  latestNote: EltmNoteDto | null
}

/** One chat entry of a `GET /api/chats` page (`ChatListPage`): id + title + the persona record. */
export interface ChatInfo {
  id: string
  title: string
  /** the persona id of the chat's last successful run; only pre-fills the picker */
  personaId: number
}

/**
 * One page of `GET /api/chats` (keyset pagination on the immutable chat id,
 * newest first — the cursor anchors a position in that order, so concurrent
 * deletes between pages never skip a row). `nextCursor` is absent when the
 * list is exhausted. Mirror of `agent/chat/ChatStore.kt` `ChatListPage`.
 */
export interface ChatListPage {
  chats: ChatInfo[]
  nextCursor?: string
}

/**
 * The chat export/import payload (`GET /api/chats/{id}/export` response =
 * `POST /api/chats/import` request; server/Dtos.kt `ChatExportPayload`):
 * the title plus the neutral-format history, no chat id — an import always
 * mints a fresh one.
 */
export interface ChatExport {
  title: string
  messages: ChatMessage[]
}

/**
 * One agent persona (`GET /api/personas`): the persona half of the system
 * prompt plus a tool-namespace whitelist. The code-only default persona
 * (id [DEFAULT_PERSONA_ID], read-only) leads the list.
 */
export interface Persona {
  id: number
  name: string
  systemPrompt: string
  /** namespace whitelist over the chat loop's tools; [] = all namespaces (authority: `agent/persona/Persona.kt`) */
  allowedNamespaces: string[]
}

/** mirrors the backend's reserved default persona id 0 (agent/persona/Persona.kt) */
export const DEFAULT_PERSONA_ID = 0

/**
 * One entry of the personas export/import payload (`GET /api/personas/export`
 * response = `POST /api/personas/import` request; authority:
 * `agent/persona/PersonaTransfer.kt`). An array of these, one per persona row
 * in creation order — the code-only default persona is never exported.
 */
export interface PersonaExportEntry {
  name: string
  systemPrompt: string
  /** namespace whitelist over the chat loop's tools; [] = all namespaces */
  allowedNamespaces: string[]
}

/** The personas export file: the entry array itself. */
export type PersonaExportFile = PersonaExportEntry[]

/**
 * The `POST /api/personas/import` response: the imported names split by
 * outcome, entry order preserved (authority: `PersonaTransfer.kt`
 * `PersonaImportSummary`).
 */
export interface PersonaImportResult {
  created: string[]
  skipped: string[]
}

export interface StreamEvent {
  event: string
  data: string
}
