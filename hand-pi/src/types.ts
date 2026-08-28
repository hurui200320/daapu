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
  /**
   * User messages only: when the message was sent (UTC ISO instant). The
   * brain stamps it and regenerates the per-request `<meta>` time anchors
   * from it; the hand is unaware of it and ignores it (converters only
   * read known fields).
   */
  createdAt?: string;
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

/**
 * One tool advertisement in the neutral format: pure advertisement —
 * the name/description/schema the model sees. The execution budget is a
 * brain-side concern (the brain enforces it on the callback route); the
 * hand never sees or enforces it.
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
  /** The system prompt; never a message in the chat. */
  systemPrompt?: string;
  /**
   * The brain's tool-listing endpoint (`GET {toolListUrl}?runId=...`):
   * the hand queries it BEFORE every LLM request and uses the returned
   * set for that round — the tool set is never captured statically in the
   * request, so the run always sees the provider's latest advertisements.
   * Omitted = no tools at all: the hand makes NO brain-side HTTP call for
   * the run (no tool-list GET, and no callback can fire — a tool-less run
   * works without any HTTP server next to it).
   */
  toolListUrl?: string;
  /** The output budget for this call; always explicit. */
  maxTokens: number;
  runId: string;
  /** Required iff `toolListUrl` is present (tools may be advertised). */
  toolCallbackUrl?: string;
  /** Round cap; 0 = unlimited. */
  maxRounds: number;
  /**
   * Total transient attempts per round (a `maxRetries` of 1 allows a single
   * attempt); 0 = unlimited. Mirrors `/v1/embed`'s `maxRetries` semantics.
   */
  maxRetries: number;
  /** Idle timeout per streamed round in ms; 0 = disabled. */
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
 * The `/v1/embed` request: one OpenAI-compatible embedding call, fully
 * described per request (the hand holds no catalog and no defaults). The
 * run-policy knobs mirror `/v1/run`'s: `maxRetries` (0 = unlimited) and
 * `timeoutMs` (0 = disabled) are the brain's per-call budget.
 */
export interface EmbedRequest {
  model: { baseUrl: string; apiKey: string; modelId: string };
  /**
   * The output dimensionality the brain's catalog entry pins; the hand
   * sends it to the gateway and the brain verifies the response against
   * it (never silently truncated — a gateway that cannot honor it answers
   * an error).
   */
  dimensions: number;
  /** Non-empty, non-blank strings. */
  input: string[];
  maxRetries: number;
  timeoutMs: number;
  /**
   * Extra root-level fields merged into the `{baseUrl}/embeddings` request
   * body (gateway-specific knobs the contract does not model, e.g.
   * deepinfra's `service_tier: "priority"`). Must not collide with the
   * hand-managed fields (`model`, `input`, `dimensions`). Omitted = no
   * extra fields.
   */
  additionalProperties?: Record<string, unknown>;
}

export interface EmbedUsage {
  promptTokens: number;
  totalTokens: number;
}

export interface EmbedResult {
  /** One vector per input item, in order. */
  vectors: number[][];
  /** `vectors[0].length`. */
  dimensions: number;
  /** Passed through when the provider reports it; omitted otherwise. */
  usage?: EmbedUsage;
}
