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
 *
 * Field semantics are documented at the Kotlin authority (`hand/HandDtos.kt`,
 * `agent/chat/ChatMessage.kt`) — this file does not restate them; comments
 * here cover only hand-side behavior this service implements.
 */

export type ChatMessageRole = "user" | "assistant" | "tool_result";

export interface ChatMessageMeta {
  /**
   * The FULL prompt size (`prompt_tokens`); semantics owned by Kotlin —
   * see `ChatMessageMeta` (`agent/chat/ChatMessage.kt`).
   */
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  modelId?: string;
}

export interface ChatMessage {
  role: ChatMessageRole;
  parts: ChatMessagePart[];
  /**
   * User messages only; semantics owned by Kotlin —
   * see `ChatMessage` (`agent/chat/ChatMessage.kt`).
   */
  createdAt?: string;
  meta?: ChatMessageMeta;
  /** Assistant messages only — see `agent/chat/ChatMessage.kt`. */
  finishReason?: string;
}

export type AttachmentKind = "image" | "video" | "audio" | "file";

export type AttachmentContent = { type: "base64"; base64: string };

export type ChatMessagePart =
  | { type: "text"; text: string }
  | { type: "reasoning"; content: string }
  /** non-blank — see `ChatMessagePart.ToolCall.id` (`agent/chat/ChatMessage.kt`). */
  | { type: "tool_call"; id: string; tool: string; args: Record<string, unknown> }
  | { type: "tool_result"; id: string; tool: string; parts: ContentPart[]; isError: boolean }
  | { type: "attachment"; kind: AttachmentKind; content: AttachmentContent; mimeType: string };

export type ContentPart =
  | { type: "text"; text: string }
  | { type: "attachment"; kind: AttachmentKind; content: AttachmentContent; mimeType: string };

/** See `HandModelSpec` (`hand/HandDtos.kt`): the hand has no catalog. */
export interface ModelSpec {
  /** Full OpenAI-compatible base URL, e.g. `http://10.233.1.8:8002/v1`. */
  baseUrl: string;
  apiKey: string;
  modelId: string;
  contextWindow: number;
  maxOutputTokens: number;
  reasoning: boolean;
  /** See `HandModelSpec.reasoningEffort` (`hand/HandDtos.kt`). */
  reasoningEffort?: string;
  // TODO: the wire carries text and image only; audio/video/document
  //       attachments are declared by the brain's capability model but not
  //       supported yet (convert.ts rejects those kinds). Extend the union
  //       here together with convert.ts and the brain's HandMappers when
  //       support lands.
  input: ("text" | "image")[];
}

export type JsonSchema = Record<string, unknown>;

/**
 * One tool advertisement in the neutral format: the name/description/schema
 * the model sees. The hand never sees or enforces the execution budget
 * (see `HandRunRequest.toolListUrl` in `hand/HandDtos.kt`).
 */
export interface ToolSpec {
  name: string;
  description: string;
  schema: JsonSchema;
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

export interface RunRequest {
  model: ModelSpec;
  messages: ChatMessage[];
  /** No `system` role — see `agent/chat/ChatMessage.kt`. */
  systemPrompt?: string;
  /**
   * The brain's tool-listing endpoint (`GET {toolListUrl}?runId=...`);
   * contract semantics at `HandRunRequest.toolListUrl`
   * (`hand/HandDtos.kt`). Omitted: the hand makes NO brain-side HTTP call
   * for the run (no tool-list GET, no callback), so a tool-less run works
   * without any HTTP server next to it.
   */
  toolListUrl?: string;
  /** The output budget for this call; always explicit. */
  maxTokens: number;
  runId: string;
  /** Required iff `toolListUrl` is present (tools may be advertised). */
  toolCallbackUrl?: string;
  /** See `HandRunRequest` (`hand/HandDtos.kt`). */
  maxRounds: number;
  /** See `HandRunRequest` (`hand/HandDtos.kt`). */
  maxRetries: number;
  /** See `HandRunRequest` (`hand/HandDtos.kt`). */
  streamIdleTimeoutMs: number;
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

/**
 * The `/v1/embed` request; contract semantics at `HandEmbedRequest`
 * (`hand/HandDtos.kt`).
 */
export interface EmbedRequest {
  model: { baseUrl: string; apiKey: string; modelId: string };
  /**
   * See `HandEmbedRequest` (`hand/HandDtos.kt`): never silently
   * truncated — a gateway that cannot honor it answers an error.
   */
  dimensions: number;
  /** Non-empty, non-blank strings. */
  input: string[];
  maxRetries: number;
  timeoutMs: number;
  /**
   * Extra root-level fields merged into the `{baseUrl}/embeddings` request
   * body — semantics at `HandEmbedRequest` (`hand/HandDtos.kt`).
   */
  additionalProperties?: Record<string, unknown>;
}

export interface EmbedUsage {
  promptTokens: number;
  totalTokens: number;
}

/** See `HandEmbedResult` (`hand/HandDtos.kt`). */
export interface EmbedResult {
  vectors: number[][];
  /** `vectors[0].length`. */
  dimensions: number;
  usage?: EmbedUsage;
}
