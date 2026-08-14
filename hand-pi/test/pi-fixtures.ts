import type { AssistantMessage as PiAssistantMessage } from "@earendil-works/pi-ai";

const ZERO_USAGE = {
  input: 0,
  output: 0,
  cacheRead: 0,
  cacheWrite: 0,
  totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

/** Builds a minimal pi-ai assistant message for unit tests. */
export function makePiMessage(overrides: Partial<PiAssistantMessage>): PiAssistantMessage {
  return {
    role: "assistant",
    content: [],
    api: "openai-completions",
    provider: "daapu",
    model: "test-model",
    usage: { ...ZERO_USAGE },
    stopReason: "stop",
    timestamp: Date.now(),
    ...overrides,
  };
}
