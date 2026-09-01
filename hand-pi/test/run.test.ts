import { describe, expect, it } from "vitest";
import type { AssistantMessage as PiAssistantMessage } from "@earendil-works/pi-ai";
import { upgradeStopWithToolCalls } from "../src/run.js";
import { makePiMessage } from "./pi-fixtures.js";

type ContentBlock = PiAssistantMessage["content"][number];

const CALL: ContentBlock = { type: "toolCall", id: "call_1", name: "search", arguments: { query: "hello" } };
const TEXT: ContentBlock = { type: "text", text: "Let me check." };

describe("upgradeStopWithToolCalls", () => {
  it("upgrades a stop finish carrying tool calls to toolUse", () => {
    const message = makePiMessage({ stopReason: "stop", content: [CALL] });
    const upgraded = upgradeStopWithToolCalls(message);
    expect(upgraded.stopReason).toBe("toolUse");
    expect(upgraded.content).toEqual([CALL]);
    // the original message is not mutated
    expect(message.stopReason).toBe("stop");
  });

  it("upgrades a stop finish with text ahead of the calls", () => {
    const message = makePiMessage({ stopReason: "stop", content: [TEXT, CALL] });
    expect(upgradeStopWithToolCalls(message).stopReason).toBe("toolUse");
  });

  it("leaves a stop finish without tool calls untouched", () => {
    const message = makePiMessage({ stopReason: "stop", content: [TEXT] });
    expect(upgradeStopWithToolCalls(message)).toBe(message);
  });

  it("leaves an already-toolUse finish untouched", () => {
    const message = makePiMessage({ stopReason: "toolUse", content: [CALL] });
    expect(upgradeStopWithToolCalls(message)).toBe(message);
  });

  it("leaves a length finish with tool calls untouched (the partial-preservation path)", () => {
    const message = makePiMessage({ stopReason: "length", content: [CALL] });
    expect(upgradeStopWithToolCalls(message)).toBe(message);
  });
});
