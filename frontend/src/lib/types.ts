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

interface TextPart {
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
type ChatContentPart = TextPart | ChatAttachmentPart

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

export interface ModelInfo {
  id: string
  vision: boolean
  contextLength: number | null
  maxOutputTokens: number | null
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

/** One entry of `GET /api/chats`: the user-visible chat title. */
export interface ChatInfo {
  id: string
  title: string
}

export interface StreamEvent {
  event: string
  data: string
}
