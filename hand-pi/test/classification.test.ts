import { describe, expect, it } from "vitest";
import { classifyTerminal } from "../src/run.js";
import { makePiMessage } from "./pi-fixtures.js";

const USAGE = {
  input: 0,
  output: 0,
  cacheRead: 0,
  cacheWrite: 0,
  totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

describe("classifyTerminal", () => {
  it("classifies a clean stop as ok", () => {
    const result = classifyTerminal("done", makePiMessage({ stopReason: "stop" }), 131000, 40000);
    expect(result).toEqual({ ok: true, finishReason: "stop" });
  });

  it("classifies toolUse as ok tool_calls", () => {
    const result = classifyTerminal("done", makePiMessage({ stopReason: "toolUse" }), 131000, 40000);
    expect(result).toEqual({ ok: true, finishReason: "tool_calls" });
  });

  it("classifies a length finish near the window as context_exhausted", () => {
    const message = makePiMessage({
      stopReason: "length",
      usage: { ...USAGE, input: 100000, output: 300, totalTokens: 100300 },
    });
    const result = classifyTerminal("done", message, 131000, 40000);
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.type).toBe("context_exhausted");
    }
  });

  it("folds cache tokens into the input size for length classification", () => {
    const message = makePiMessage({
      stopReason: "length",
      usage: { ...USAGE, input: 60000, cacheRead: 40000, output: 300, totalTokens: 100300 },
    });
    const result = classifyTerminal("done", message, 131000, 40000);
    expect(result).toMatchObject({ ok: false, error: { type: "context_exhausted" } });
  });

  it("classifies a length finish far from the window as output_budget_exhausted", () => {
    const message = makePiMessage({
      stopReason: "length",
      usage: { ...USAGE, input: 10000, output: 40000, totalTokens: 50000 },
    });
    const result = classifyTerminal("done", message, 131000, 40000);
    expect(result).toMatchObject({ ok: false, error: { type: "output_budget_exhausted" } });
  });

  it("classifies a length finish without usage as output_budget_exhausted", () => {
    const message = makePiMessage({ stopReason: "length", usage: undefined as never });
    const result = classifyTerminal("done", message, 131000, 40000);
    expect(result).toMatchObject({ ok: false, error: { type: "output_budget_exhausted" } });
  });

  it("classifies a content_filter error", () => {
    const message = makePiMessage({
      stopReason: "error",
      errorMessage: "Provider finish_reason: content_filter",
    });
    const result = classifyTerminal("error", message, 131000, 40000);
    expect(result).toMatchObject({ ok: false, error: { type: "content_filter" } });
  });

  it("classifies a gateway-side overflow rejection as context_exhausted", () => {
    const message = makePiMessage({
      stopReason: "error",
      errorMessage:
        '400: {"message":"This model\'s maximum context length is 131072 tokens. However, you requested 200000 tokens."}',
    });
    const result = classifyTerminal("error", message, 131000, 40000);
    expect(result).toMatchObject({ ok: false, error: { type: "context_exhausted" } });
  });

  it("classifies a bare 413 as context_exhausted", () => {
    const message = makePiMessage({
      stopReason: "error",
      errorMessage: "413 status code (no body)",
    });
    const result = classifyTerminal("error", message, 131000, 40000);
    expect(result).toMatchObject({ ok: false, error: { type: "context_exhausted" } });
  });

  it("classifies a generic upstream error as upstream", () => {
    const message = makePiMessage({
      stopReason: "error",
      errorMessage: '500: {"message":"Internal server error"}',
    });
    const result = classifyTerminal("error", message, 131000, 40000);
    expect(result).toMatchObject({ ok: false, error: { type: "upstream" } });
  });

  it("classifies a truncated stream as upstream (not overflow)", () => {
    const message = makePiMessage({
      stopReason: "error",
      errorMessage: "Stream ended without finish_reason",
    });
    const result = classifyTerminal("error", message, 131000, 40000);
    expect(result).toMatchObject({ ok: false, error: { type: "upstream" } });
  });

  it("does not classify rate limits as overflow", () => {
    const message = makePiMessage({
      stopReason: "error",
      errorMessage: '429: {"message":"Too many requests, rate limit exceeded"}',
    });
    const result = classifyTerminal("error", message, 131000, 40000);
    expect(result).toMatchObject({ ok: false, error: { type: "upstream" } });
  });
});
