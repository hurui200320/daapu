/**
 * Wire DTOs of the hand-pi service.
 *
 * The message DTOs mirror daapu's stored chat JSON (`agent/chat/ChatMessage.kt`,
 * kotlinx-serialization with polymorphic `"type"` tags) — one schema across
 * database, Kotlin brain, and this service. The Kotlin codec is configured
 * with `ignoreUnknownKeys`, `explicitNulls=false`, `encodeDefaults=true`:
 * absent optional fields are omitted, and `isError: false` is written
 * explicitly. Outbound messages keep that shape so Kotlin round-trips them
 * losslessly.
 *
 * The hand trusts Kotlin to send valid messages (Kotlin is the format's
 * authority and validates on encode): request handlers only check the
 * request envelope, never the message shapes.
 */

export type ChatMessageRole = "user" | "assistant" | "tool_result";

export interface ChatMessageMeta {
  /**
   * The FULL prompt size (`prompt_tokens`), never pi-ai's cache-subtracted
   * input count — the Kotlin classifier and the proactive compaction
   * trigger depend on it. Required on every response the hand produces:
   * `assembleAssistantMessage` fails when the provider reported no usage.
   */
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  modelId?: string;
}

export interface ChatMessage {
  role: ChatMessageRole;
  parts: ChatMessagePart[];
  meta?: ChatMessageMeta;
  /** Assistant messages only; required non-blank there. */
  finishReason?: string;
}

export type AttachmentKind = "image" | "video" | "audio" | "file";

export type AttachmentContent = { type: "base64"; base64: string };

export type ChatMessagePart =
  | { type: "text"; text: string }
  | { type: "reasoning"; content: string }
  /** `id` is non-blank. */
  | { type: "tool_call"; id: string; tool: string; args: Record<string, unknown> }
  | { type: "tool_result"; id: string; tool: string; parts: ContentPart[]; isError: boolean }
  | { type: "attachment"; kind: AttachmentKind; content: AttachmentContent; mimeType: string };

export type ContentPart =
  | { type: "text"; text: string }
  | { type: "attachment"; kind: AttachmentKind; content: AttachmentContent; mimeType: string };

/** Per-request model description; the hand has no catalog. */
export interface ModelSpec {
  /** Full OpenAI-compatible base URL, e.g. `http://10.233.1.8:8002/v1`. */
  baseUrl: string;
  apiKey: string;
  modelId: string;
  contextWindow: number;
  maxOutputTokens: number;
  reasoning: boolean;
  /** e.g. "high"; reasoning models only (omitted otherwise). */
  reasoningEffort?: string;
  input: ("text" | "image")[];
}

export type JsonSchema = Record<string, unknown>;

export interface ToolSpec {
  name: string;
  description: string;
  schema: JsonSchema;
  /**
   * The tool's execution budget in seconds, 0 = no timeout. Required on
   * every advertised tool: the callback POST waits `timeoutSeconds + 30s`
   * (the brain enforces the budget itself and always answers in time), a
   * 0-timeout tool falls back to the run-level `callbackTimeoutMs`.
   */
  timeoutSeconds: number;
}

export type HandErrorType =
  | "invalid_request"
  | "auth"
  | "upstream"
  | "context_exhausted"
  | "output_budget_exhausted"
  | "content_filter"
  | "tool_transport"
  | "round_limit"
  | "empty_response"
  | "internal";

export interface HandError {
  type: HandErrorType;
  message: string;
}

export interface CompleteRequest {
  model: ModelSpec;
  messages: ChatMessage[];
  /** The system prompt; never a message in the chat. */
  systemPrompt?: string;
  tools?: ToolSpec[];
  /** The output budget for this call; always explicit. */
  maxTokens: number;
}

export type CompleteResponse =
  | { ok: true; message: ChatMessage; finishReason: string }
  | { ok: false; error: HandError };

export interface RunRequest extends CompleteRequest {
  runId: string;
  /** Required iff `tools` is non-empty. */
  toolCallbackUrl?: string;
  /** Round cap; 0 = unlimited. */
  maxRounds: number;
  /** Transient retries per round; 0 = unlimited. */
  maxRetries: number;
  /** Idle timeout per streamed round in ms; 0 = disabled. */
  streamIdleTimeoutMs: number;
  /** Tool callback POST timeout in ms; 0 = no timeout. */
  callbackTimeoutMs: number;
}

/** Error thrown by handlers; mapped onto the `{ok:false,error}` contract. */
export class HandFailure extends Error {
  readonly handError: HandError;

  constructor(handError: HandError) {
    super(handError.message);
    this.name = "HandFailure";
    this.handError = handError;
  }
}
