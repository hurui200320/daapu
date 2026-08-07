/**
 * Loose mirror of the koog `Message` JSON that the backend serves verbatim
 * (see PostgresChatHistoryProvider + its golden-format tests). Only the
 * fields the UI renders are modeled; the `type` discriminators are pinned
 * against koog 1.1.1 in the golden tests.
 */

export const MSG_SYSTEM = 'ai.koog.prompt.message.Message.System'
export const MSG_USER = 'ai.koog.prompt.message.Message.User'
export const MSG_ASSISTANT = 'ai.koog.prompt.message.Message.Assistant'

export const PART_TEXT = 'ai.koog.prompt.message.MessagePart.Text'
export const PART_REASONING = 'ai.koog.prompt.message.MessagePart.Reasoning'
export const PART_ATTACHMENT = 'ai.koog.prompt.message.MessagePart.Attachment'
export const PART_TOOL_CALL = 'ai.koog.prompt.message.MessagePart.Tool.Call'
export const PART_TOOL_RESULT = 'ai.koog.prompt.message.MessagePart.Tool.Result'

export const ATTACH_IMAGE = 'ai.koog.prompt.message.AttachmentSource.Image'
export const CONTENT_BASE64 = 'ai.koog.prompt.message.AttachmentContent.Binary.Base64'
export const CONTENT_URL = 'ai.koog.prompt.message.AttachmentContent.URL'

export interface KoogMessage {
  type: string
  parts: KoogPart[]
  finishReason?: string
}

export interface KoogPart {
  type: string
  text?: string
  content?: string[]
  id?: string
  tool?: string
  args?: string
  output?: string
  parts?: KoogPart[]
  source?: KoogAttachmentSource
}

export interface KoogAttachmentSource {
  type: string
  format?: string
  mimeType?: string
  fileName?: string
  content?: {
    type: string
    base64?: string
    url?: string
    text?: string
  }
}

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
