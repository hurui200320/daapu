import { stream } from "@earendil-works/pi-ai/api/openai-completions";
import type { OpenAICompletionsOptions } from "@earendil-works/pi-ai/api/openai-completions";
import type {
  AssistantMessage as PiAssistantMessage,
  AssistantMessageEventStream,
  Context as PiContext,
  Model as PiModel,
  Tool as PiTool,
} from "@earendil-works/pi-ai";
import type { ModelSpec, ToolSpec } from "./types.js";

/** Fixed provider id, matching none of pi-ai's auto-detect heuristics. */
const HAND_PROVIDER_ID = "daapu";

/**
 * Synthesizes the full pi-ai Model from a ModelSpec. The `reasoning` flag is
 * required: every reasoning code path in pi-ai (the `reasoning_effort`
 * param, the `developer`-role system prompt) is gated on it. Likewise the
 * `input` modalities — pi-ai silently drops images for models without
 * `"image"`; Kotlin's capability check remains the real gate.
 */
export function buildModel(spec: ModelSpec): PiModel<"openai-completions"> {
  return {
    id: spec.modelId,
    name: spec.modelId,
    api: "openai-completions",
    provider: HAND_PROVIDER_ID,
    baseUrl: spec.baseUrl,
    reasoning: spec.reasoning,
    input: spec.input,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: spec.contextWindow,
    maxTokens: spec.maxOutputTokens,
  };
}

function toPiTools(tools: ToolSpec[]): PiTool[] {
  return tools.map((tool) => ({
    name: tool.name,
    description: tool.description,
    parameters: tool.schema as PiTool["parameters"],
  }));
}

interface StreamOptions {
  apiKey: string;
  /** Required per request: the brain always sends its output budget. */
  maxTokens: number;
  reasoningEffort?: string;
  signal?: AbortSignal;
}

export type TerminalOutcome =
  { kind: "done"; message: PiAssistantMessage } | { kind: "error"; message: PiAssistantMessage; aborted: boolean };

/**
 * Opens pi-ai's direct `stream()` (never `streamSimple`). pi-ai surfaces
 * everything — including upstream failures — as stream events, never as
 * exceptions to the caller. Empty tool lists are omitted (some gateways
 * reject `tools: []`).
 */
export function openStream(
  model: PiModel<"openai-completions">,
  context: PiContext,
  tools: ToolSpec[] | undefined,
  options: StreamOptions,
): AssistantMessageEventStream {
  const piTools = tools !== undefined && tools.length > 0 ? toPiTools(tools) : undefined;
  return stream(
    model,
    { systemPrompt: context.systemPrompt, messages: context.messages, tools: piTools },
    {
      apiKey: options.apiKey,
      maxTokens: options.maxTokens,
      reasoningEffort: options.reasoningEffort as OpenAICompletionsOptions["reasoningEffort"],
      signal: options.signal,
    },
  );
}
