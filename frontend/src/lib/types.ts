/**
 * Loose mirror of the framework-neutral chat history format the backend
 * serves (see history/HistoryMessage.kt + the golden-format tests in
 * HistoryCodecTest). The `type`/`role` discriminators are our own short
 * lowercase names — no framework type strings cross the API.
 */

export type Role = 'system' | 'user' | 'assistant' | 'tool'

export interface HistoryMeta {
  timestamp?: string
  inputTokens?: number
  outputTokens?: number
  totalTokens?: number
  modelId?: string
}

export interface HistoryMessage {
  role: Role
  parts: HistoryPart[]
  meta?: HistoryMeta
  finishReason?: string
}

export type HistoryPart = TextPart | ReasoningPart | ToolCallPart | ToolResultPart | AttachmentPart

export interface TextPart {
  type: 'text'
  text: string
}

export interface ReasoningPart {
  type: 'reasoning'
  content: string[]
}

export interface ToolCallPart {
  type: 'tool_call'
  /** required by the format: a blank id would brick the chat on re-send */
  id: string
  tool: string
  args: string
}

export interface ToolResultPart {
  type: 'tool_result'
  /** required by the format: the id of the tool_call this result answers */
  id: string
  tool: string
  parts: HistoryContentPart[]
  isError?: boolean
}

/** Text and attachments may also appear nested inside a tool_result. */
export type HistoryContentPart = TextPart | AttachmentPart

export type AttachmentKind = 'image' | 'video' | 'audio' | 'file'

export interface AttachmentPart {
  type: 'attachment'
  kind: AttachmentKind
  content: AttachmentContent
  format: string
  mimeType: string
  fileName?: string
}

// URL attachment content is deliberately not supported (blocked at the
// backend boundary) until a real use case exists.
export type AttachmentContent =
  | { type: 'base64'; base64: string }
  | { type: 'text'; text: string }

export interface ModelInfo {
  id: string
  vision: boolean
  contextLength: number | null
  maxOutputTokens: number | null
}

export interface MemoryDto {
  id: number
  lastUpdate: string
  content: string
}

export interface StreamEvent {
  event: string
  data: string
}
