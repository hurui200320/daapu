/**
 * Loose mirror of the framework-neutral chat format the backend
 * serves (see chat/ChatMessage.kt + the golden-format tests in
 * ChatCodecTest). The `type`/`role` discriminators are our own short
 * lowercase names — no framework type strings cross the API.
 */

export type Role = 'system' | 'user' | 'assistant' | 'tool_result'

export interface ChatMessageMeta {
  timestamp?: string
  inputTokens?: number
  outputTokens?: number
  totalTokens?: number
  modelId?: string
}

export interface ChatMessage {
  role: Role
  parts: ChatMessagePart[]
  meta?: ChatMessageMeta
  finishReason?: string
}

export type ChatMessagePart = TextPart | ReasoningPart | ToolCallPart | ChatToolResultPart | ChatAttachmentPart

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

export type AttachmentKind = 'image' | 'video' | 'audio' | 'file'

export interface ChatAttachmentPart {
  type: 'attachment'
  kind: AttachmentKind
  content: AttachmentContent
  mimeType: string
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
