import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  REPLAY_THINKING_SIGNATURE,
  assembleAssistantMessage,
  toPiContext,
} from "../src/convert.js";
import { HandFailure } from "../src/types.js";
import { makePiMessage } from "./pi-fixtures.js";

const GOLDEN = readFileSync(new URL("./fixtures/chat-golden.json", import.meta.url), "utf8");

describe("golden fixture", () => {
  it("converts the daapu chat format to the pi context with the out-of-band system prompt", () => {
    const messages = JSON.parse(GOLDEN);
    expect(messages).toHaveLength(4);
    const context = toPiContext(messages, "You are a concise assistant.");

    expect(context.systemPrompt).toBe("You are a concise assistant.");
    expect(context.messages).toHaveLength(4);

    const user = context.messages[0];
    expect(user?.role).toBe("user");
    expect(user?.content).toEqual([
      { type: "text", text: "Look at this image and search for the weather in Berlin." },
      {
        type: "image",
        data: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        mimeType: "image/png",
      },
    ]);

    const assistant = context.messages[1];
    expect(assistant?.role).toBe("assistant");
    expect(assistant?.content).toEqual([
      { type: "thinking", thinking: "The user wants the weather.", thinkingSignature: "reasoning_content" },
      { type: "thinking", thinking: "I should call the search tool.", thinkingSignature: "reasoning_content" },
      { type: "text", text: "Let me look that up." },
      { type: "toolCall", id: "call_golden_1", name: "get_weather", arguments: { city: "Berlin" } },
    ]);

    const toolResult = context.messages[2];
    expect(toolResult).toEqual({
      role: "toolResult",
      toolCallId: "call_golden_1",
      toolName: "get_weather",
      content: [{ type: "text", text: "Berlin: 22C, sunny." }],
      isError: false,
      timestamp: expect.any(Number),
    });

    const last = context.messages[3];
    expect(last?.role).toBe("assistant");
    expect(last?.content).toEqual([{ type: "text", text: "It is 22C and sunny in Berlin." }]);
  });
});

describe("toPiContext", () => {
  it("passes the system prompt through and drops an empty one", () => {
    const messages = [{ role: "user", parts: [{ type: "text", text: "hi" }] }];
    expect(toPiContext(messages, "be nice").systemPrompt).toBe("be nice");
    expect(toPiContext(messages, undefined).systemPrompt).toBeUndefined();
    expect(toPiContext(messages, "").systemPrompt).toBeUndefined();
  });

  it("maps a tool result image attachment to an image block", () => {
    const context = toPiContext(
      [
        {
          role: "tool_result",
          parts: [
            {
              type: "tool_result",
              id: "call_1",
              tool: "search",
              parts: [
                {
                  type: "attachment",
                  kind: "image",
                  content: { type: "base64", base64: "AAAA" },
                  mimeType: "image/png",
                },
              ],
              isError: false,
            },
          ],
        },
      ],
      undefined,
    );
    expect(context.messages[0]).toMatchObject({
      role: "toolResult",
      content: [{ type: "image", data: "AAAA", mimeType: "image/png" }],
    });
  });

  it("rejects video, audio, and file attachments", () => {
    for (const kind of ["video", "audio", "file"]) {
      expect(() =>
        toPiContext(
          [
            {
              role: "user",
              parts: [
                {
                  type: "attachment",
                  kind: kind as "video",
                  content: { type: "base64", base64: "AAAA" },
                  mimeType: "x/y",
                },
              ],
            },
          ],
          undefined,
        ),
      ).toThrowError(HandFailure);
    }
  });

  it("passes tool-call arguments through as objects", () => {
    const context = toPiContext(
      [
        {
          role: "assistant",
          parts: [{ type: "tool_call", id: "call_1", tool: "search", args: { query: "hello" } }],
          finishReason: "tool_calls",
        },
      ],
      undefined,
    );
    expect(context.messages[0]?.content).toEqual([
      { type: "toolCall", id: "call_1", name: "search", arguments: { query: "hello" } },
    ]);
  });

  it("rejects part types that do not match the role", () => {
    expect(() =>
      toPiContext([{ role: "user", parts: [{ type: "reasoning", content: "x" }] }], undefined),
    ).toThrowError(HandFailure);
  });

  it("replays thinking blocks under the synthesized reasoning signature", () => {
    const context = toPiContext(
      [
        {
          role: "assistant",
          parts: [{ type: "reasoning", content: "deep thoughts" }],
          finishReason: "stop",
        },
      ],
      undefined,
    );
    expect(context.messages[0]?.content).toEqual([
      {
        type: "thinking",
        thinking: "deep thoughts",
        thinkingSignature: REPLAY_THINKING_SIGNATURE,
      },
    ]);
  });

  it("tags replayed thinking with the current model for reasoning models regardless of the producing model", () => {
    const context = toPiContext(
      [
        {
          role: "assistant",
          parts: [{ type: "reasoning", content: "deep thoughts" }],
          meta: { inputTokens: 10, outputTokens: 4, totalTokens: 14, modelId: "some/other-model" },
          finishReason: "stop",
        },
      ],
      undefined,
      { reasoning: true, modelId: "cerebras/gpt-oss-120b" },
    );
    const assistant = context.messages[0];
    expect(assistant?.model).toBe("cerebras/gpt-oss-120b");
    expect(assistant?.content).toEqual([
      {
        type: "thinking",
        thinking: "deep thoughts",
        thinkingSignature: REPLAY_THINKING_SIGNATURE,
      },
    ]);
  });

  it("drops thinking entirely for a non-reasoning model", () => {
    const context = toPiContext(
      [
        {
          role: "assistant",
          parts: [
            { type: "reasoning", content: "deep thoughts" },
            { type: "text", text: "previous answer" },
          ],
          meta: { inputTokens: 10, outputTokens: 4, totalTokens: 14, modelId: "cerebras/gpt-oss-120b" },
          finishReason: "stop",
        },
      ],
      undefined,
      { reasoning: false, modelId: "plain-model" },
    );
    expect(context.messages[0]?.content).toEqual([{ type: "text", text: "previous answer" }]);
  });
});

describe("assembleAssistantMessage", () => {
  it("folds cache tokens into the full prompt size and maps toolUse", () => {
    const pi = makePiMessage({
      stopReason: "toolUse",
      content: [
        { type: "thinking", thinking: "about to call" },
        { type: "text", text: "checking" },
        { type: "toolCall", id: "call_9", name: "search", arguments: { query: "hello" } },
      ],
      usage: {
        input: 35,
        output: 20,
        cacheRead: 60,
        cacheWrite: 5,
        totalTokens: 120,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
      },
    });
    const { message, finishReason } = assembleAssistantMessage(pi, "cerebras/gpt-oss-120b");
    expect(finishReason).toBe("tool_calls");
    expect(message.role).toBe("assistant");
    expect(message.finishReason).toBe("tool_calls");
    expect(message.parts).toEqual([
      { type: "reasoning", content: "about to call" },
      { type: "text", text: "checking" },
      { type: "tool_call", id: "call_9", tool: "search", args: { query: "hello" } },
    ]);
    expect(message.meta).toMatchObject({
      inputTokens: 100,
      outputTokens: 20,
      totalTokens: 120,
      modelId: "cerebras/gpt-oss-120b",
    });
    expect(message.meta).not.toHaveProperty("timestamp");
  });

  it("maps a clean stop", () => {
    const pi = makePiMessage({
      stopReason: "stop",
      content: [{ type: "text", text: "done" }],
      usage: { ...makePiMessage({}).usage, input: 10, output: 2, totalTokens: 12 },
    });
    const { message, finishReason } = assembleAssistantMessage(pi, "m");
    expect(finishReason).toBe("stop");
    expect(message.meta).toMatchObject({ inputTokens: 10, outputTokens: 2, totalTokens: 12 });
  });

  it("fails when the provider reported no usage", () => {
    // pi-ai initializes usage to zeros; a real terminal message always
    // carries output tokens, so all-zero usage means the gateway ignored
    // stream_options.include_usage — daapu must not fabricate a snapshot
    const pi = makePiMessage({
      stopReason: "stop",
      content: [{ type: "text", text: "done" }],
    });
    expect(() => assembleAssistantMessage(pi, "m")).toThrowError(HandFailure);
  });

  it("fails on a tool call without an id", () => {
    const pi = makePiMessage({
      stopReason: "toolUse",
      content: [{ type: "toolCall", id: "", name: "search", arguments: {} }],
    });
    expect(() => assembleAssistantMessage(pi, "m")).toThrowError(HandFailure);
  });
});
