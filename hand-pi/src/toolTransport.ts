/**
 * Outbound HTTP calls to the brain during a run: the per-round tool-list
 * query and the tool-execution callbacks. Kept separate from the round loop
 * so these outcome mappers can be unit-tested without pi-ai or SSE.
 *
 * Failure semantics (spec §3.4/§3.5): any tool-list failure is terminal for
 * the run (`tool_transport`, matching a callback `fatal`); a callback POST
 * is never retried (a retry could duplicate a side-effecting tool) and
 * applies no deadline of its own — the brain enforces each tool's execution
 * budget and always answers. A client disconnect surfaces as `abort`.
 */

import type { ContentPart, ToolSpec } from "./types.js";
import { isRecord, validateTools } from "./validate.js";

export type ToolsOutcome =
  { kind: "ok"; tools: ToolSpec[] | undefined } | { kind: "failure"; message: string } | { kind: "abort" };

export type CallbackResult =
  | { kind: "ok"; parts: ContentPart[]; isError: boolean }
  | { kind: "transport_failure"; message: string }
  | { kind: "abort" };

/** The body the hand POSTs to `{toolCallbackUrl}` for one tool execution. */
export interface ToolCallbackPayload {
  runId: string;
  id: string;
  name: string;
  args: Record<string, unknown>;
}

/**
 * Queries the brain for the run's current tool set before an LLM request
 * (`GET {toolListUrl}?runId=...`, the brain resolves the in-flight run's
 * provider by runId). The fetched list feeds that round's LLM request
 * (`tools`). Any failure is terminal (the caller maps it onto
 * `error{tool_transport}`): the brain enforces the execution budgets and
 * the provider's reachability itself, so a query failure means the run's
 * tools are unavailable — matching the callback `fatal` semantics. An
 * empty list answers "no tools" (the request may omit `toolCallbackUrl`
 * then).
 */
export async function fetchTools(
  toolListUrl: string,
  runId: string,
  token: string,
  signal: AbortSignal,
): Promise<ToolsOutcome> {
  const url = new URL(toolListUrl);
  url.searchParams.set("runId", runId);
  let response: Response;
  try {
    response = await fetch(url, {
      method: "GET",
      headers: { "x-daapu-token": token },
      signal,
    });
  } catch (error) {
    if (signal.aborted) {
      return { kind: "abort" };
    }
    return {
      kind: "failure",
      message: `tool listing failed: ${error instanceof Error ? error.message : String(error)}`,
    };
  }
  if (response.status !== 200) {
    return { kind: "failure", message: `tool listing returned HTTP ${response.status}` };
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    return { kind: "failure", message: "tool listing returned a non-JSON body" };
  }
  if (!isRecord(body) || !Array.isArray(body.tools)) {
    return { kind: "failure", message: "tool listing returned an invalid body" };
  }
  try {
    return { kind: "ok", tools: validateTools(body.tools) };
  } catch (error) {
    return {
      kind: "failure",
      message: `tool listing returned invalid tools: ${error instanceof Error ? error.message : String(error)}`,
    };
  }
}

export async function postToolCallback(
  url: string,
  token: string,
  payload: ToolCallbackPayload,
  signal: AbortSignal,
): Promise<CallbackResult> {
  let response: Response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json", "x-daapu-token": token },
      body: JSON.stringify(payload),
      // the brain always answers (it enforces the budgets itself), so the
      // client disconnect is the only abort; a brain crash drops the
      // connection and fails the fetch below
      signal,
    });
  } catch (error) {
    if (signal.aborted) {
      return { kind: "abort" };
    }
    return {
      kind: "transport_failure",
      message: `tool callback failed: ${error instanceof Error ? error.message : String(error)}`,
    };
  }
  if (response.status !== 200) {
    return { kind: "transport_failure", message: `tool callback returned HTTP ${response.status}` };
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    return { kind: "transport_failure", message: "tool callback returned a non-JSON body" };
  }
  if (!isRecord(body)) {
    return { kind: "transport_failure", message: "tool callback returned a non-object body" };
  }
  if (body.fatal !== undefined) {
    const fatal = body.fatal;
    const message =
      isRecord(fatal) && typeof fatal.message === "string" ? fatal.message : "tool callback reported a fatal error";
    return { kind: "transport_failure", message };
  }
  const parts = body.parts;
  if (!Array.isArray(parts)) {
    return { kind: "transport_failure", message: "tool callback returned invalid parts" };
  }
  return {
    kind: "ok",
    parts: parts as ContentPart[],
    isError: body.isError === true,
  };
}
