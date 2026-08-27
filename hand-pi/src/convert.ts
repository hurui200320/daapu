import type {
  AssistantMessage as PiAssistantMessage,
  Context as PiContext,
  ImageContent,
  Message as PiMessage,
  TextContent,
  ThinkingContent,
  ToolResultMessage,
  UserMessage,
} from "@earendil-works/pi-ai";
import {
  HandFailure,
  type ChatMessage,
  type ChatMessageMeta,
  type ChatMessagePart,
  type ContentPart,
} from "./types.js";
import { failInvalid } from "./validate.js";
import { fullInputTokens, ZERO_USAGE } from "./usage.js";

/**
 * Reasoning dialect used when replaying stored thinking blocks. daapu's
 * stored history keeps only the thinking text (no dialect marker), so the
 * signature cannot travel with the block; pi-ai re-emits a replayed block
 * under its `thinkingSignature` field name. `reasoning_content` is the
 * OpenAI-standard assistant field — the only dialect the hand drives.
 */
export const REPLAY_THINKING_SIGNATURE = "reasoning_content";

/**
 * Converts daapu messages into the pi-ai context. The system prompt travels
 * as a request field, never as a message. Attachments of kind
 * video/audio/file are rejected — daapu's capability check already prevents
 * them, so this is a defensive gate (the kind, not the content, is logged).
 *
 * `model.reasoning === false` drops historical thinking blocks entirely:
 * thinking must never reach a non-reasoning model, not even as plain text.
 * Reasoning models replay thinking blocks as reasoning regardless of the
 * producing model: every replayed assistant message is tagged with the
 * current run's model id, so pi-ai's same-model replay path keeps the
 * blocks instead of downgrading them to text for cross-model history.
 *
 * The hand trusts the Kotlin side to send valid messages (Kotlin validates
 * on encode), so this never validates shapes — unknown parts fail loud
 * instead of being dropped.
 */
export function toPiContext(
  messages: ChatMessage[],
  systemPrompt: string | undefined,
  model?: { reasoning?: boolean; modelId?: string },
): PiContext {
  const keepThinking = model?.reasoning ?? true;
  const piMessages: PiMessage[] = [];
  let timestamp = 1;
  for (const message of messages) {
    switch (message.role) {
      case "user":
        piMessages.push(toPiUserMessage(message, timestamp++));
        break;
      case "assistant":
        piMessages.push(toPiAssistantMessage(message, timestamp++, keepThinking, model?.modelId));
        break;
      case "tool_result": {
        const part = message.parts[0];
        if (part === undefined || part.type !== "tool_result") {
          failInvalid("tool_result messages must carry exactly one tool_result part");
        }
        piMessages.push(toPiToolResultMessage(part, timestamp++));
        break;
      }
    }
  }
  return {
    systemPrompt: systemPrompt !== undefined && systemPrompt.length > 0 ? systemPrompt : undefined,
    messages: piMessages,
  };
}

function toPiUserMessage(message: ChatMessage, timestamp: number): UserMessage {
  const content: (TextContent | ImageContent)[] = [];
  for (const part of message.parts) {
    if (part.type === "text") {
      content.push({ type: "text", text: part.text });
    } else if (part.type === "attachment") {
      content.push(attachmentToPiBlock(part));
    } else {
      failInvalid(`user messages cannot contain '${part.type}' parts`);
    }
  }
  return { role: "user", content: content.length > 0 ? content : "", timestamp };
}

function attachmentToPiBlock(part: Extract<ChatMessagePart, { type: "attachment" }>): ImageContent {
  if (part.kind === "image") {
    return { type: "image", data: part.content.base64, mimeType: part.mimeType };
  }
  console.warn(`[hand] rejecting attachment kind '${part.kind}' (unsupported in v1)`);
  failInvalid(`attachment kind '${part.kind}' is not supported`);
}

function toPiAssistantMessage(
  message: ChatMessage,
  timestamp: number,
  keepThinking: boolean,
  currentModelId?: string,
): PiAssistantMessage {
  const content: PiAssistantMessage["content"] = [];
  for (const part of message.parts) {
    switch (part.type) {
      case "text":
        content.push({ type: "text", text: part.text });
        break;
      case "reasoning": {
        if (!keepThinking) {
          break;
        }
        const block: ThinkingContent = {
          type: "thinking",
          thinking: part.content,
          thinkingSignature: REPLAY_THINKING_SIGNATURE,
        };
        content.push(block);
        break;
      }
      case "tool_call":
        content.push({
          type: "toolCall",
          id: part.id,
          name: part.tool,
          arguments: part.args,
        });
        break;
      default:
        failInvalid(`assistant messages cannot contain '${part.type}' parts`);
    }
  }
  return {
    role: "assistant",
    content,
    api: "openai-completions",
    provider: "daapu",
    // tag with the CURRENT run's model: for a reasoning model this fakes
    // pi-ai's same-model check so thinking blocks replay as reasoning even
    // when the message was produced by a different model. Cross-model
    // "downgrade thinking to text" must never happen — a non-reasoning run
    // never reaches this line with thinking blocks (keepThinking=false
    // drops them above). Falls back to the stored meta.modelId when the
    // caller (e.g. tests) passed no model.
    model: currentModelId ?? message.meta?.modelId ?? "",
    usage: ZERO_USAGE,
    stopReason: "stop",
    timestamp,
  };
}

function toPiToolResultMessage(
  part: Extract<ChatMessagePart, { type: "tool_result" }>,
  timestamp: number,
): ToolResultMessage {
  return {
    role: "toolResult",
    toolCallId: part.id,
    toolName: part.tool,
    content: part.parts.map(contentPartToPiBlock),
    isError: part.isError,
    timestamp,
  };
}

function contentPartToPiBlock(part: ContentPart): TextContent | ImageContent {
  if (part.type === "text") {
    return { type: "text", text: part.text };
  }
  return attachmentToPiBlock(part);
}

/**
 * Assembles the daapu assistant message for a pi-ai terminal message.
 * Tool-call ids must be non-blank before this is called (the round loop
 * synthesizes ids for id-less calls).
 *
 * Usage is REQUIRED: daapu's compaction trigger and exhaustion classifier
 * depend on the provider-reported input tokens, so a terminal message
 * without a usage chunk fails instead of fabricating zeros. pi-ai always
 * initializes usage to zeros, and a real terminal message always carries
 * output tokens (text or tool calls) — all-zero usage therefore means the
 * gateway ignored `stream_options.include_usage`.
 */
export function assembleAssistantMessage(
  pi: PiAssistantMessage,
  modelId: string,
): { message: ChatMessage; finishReason: string } {
  const parts: ChatMessagePart[] = [];
  for (const block of pi.content) {
    switch (block.type) {
      case "text":
        parts.push({ type: "text", text: block.text });
        break;
      case "thinking":
        parts.push({ type: "reasoning", content: block.thinking });
        break;
      case "toolCall": {
        if (block.id === undefined || block.id.length === 0) {
          throw new HandFailure({
            type: "internal",
            message: "assistant message contains a tool call without an id",
          });
        }
        parts.push({
          type: "tool_call",
          id: block.id,
          tool: block.name,
          args: (block.arguments ?? {}) as Record<string, unknown>,
        });
        break;
      }
      default:
        throw new HandFailure({
          type: "internal",
          message: `unexpected assistant content block type '${String((block as { type: string }).type)}'`,
        });
    }
  }
  const finishReason = mapStopReason(pi.stopReason);
  const usage = pi.usage ?? ZERO_USAGE;
  const inputTokens = fullInputTokens(usage);
  if (inputTokens === 0 && (usage.output ?? 0) === 0) {
    throw new HandFailure({
      type: "internal",
      message:
        "provider did not report token usage; daapu requires usage on every response " +
        "(the gateway must honor stream_options.include_usage)",
    });
  }
  const meta: ChatMessageMeta = {
    inputTokens,
    outputTokens: usage.output ?? 0,
    totalTokens: usage.totalTokens ?? 0,
    modelId,
  };
  return {
    message: { role: "assistant", parts, meta, finishReason },
    finishReason,
  };
}

function mapStopReason(stopReason: string): string {
  switch (stopReason) {
    case "stop":
      return "stop";
    case "length":
      return "length";
    case "toolUse":
      return "tool_calls";
    default:
      throw new HandFailure({
        type: "internal",
        message: `unexpected pi-ai stop reason '${stopReason}'`,
      });
  }
}
