# Codebase Review — GPT 6.1 Sol High

**Date:** 2026-10-04
**Scope:** full codebase (Kotlin brain, hand-pi service, Svelte frontend, config/schema, Docker/compose)
**Focus:** correctness, data integrity, feature behavior, test coverage — UX polish and minor security hardening are out of scope per the review brief.

### Summary

The codebase has a solid PoC structure: clear brain/hand boundaries, centralized execution policy, production-backed PostgreSQL tests, and well-separated frontend logic. The principal concerns are **data integrity and failure recovery**, not UX polish or minor security hardening.

**Verification**

- JVM: **887 tests passed**, freshly executed; no skipped tests.
- Hand: **116 tests passed**; build, typecheck, and lint passed.
- Frontend: **148 tests passed**; check and build passed. One non-failing `{@html}` lint warning; the renderer uses DOMPurify.
- Six additional regression tests, kept outside the repository, reproduced the issues described below.
- No repository source files were changed.

## Critical Issues

None.

## Major Issues

### ~~M1 — Entity merges can permanently delete concurrently committed diary notes~~ (fixed)

**Location:** `src/main/kotlin/info/skyblond/daapu/memory/eltm/postgres/EltmMergeQueries.kt:81–98`

**Problem:** When folding a duplicate relationship into its survivor, the merge first moves existing notes and then deletes the duplicate relationship. The duplicate relationship itself is not locked before moving notes.

A concurrent writer can insert and commit a note on the duplicate between those statements. The subsequent relationship deletion cascade-deletes that new note.

**Confirmed:** A PostgreSQL regression test paused the note-moving statement, committed another note, then resumed the merge. Only the original note survived.

**Fix:** Lock affected relationship rows before reading/folding them, using deterministic lock ordering. Acquire a lock that conflicts with the FK key-share lock taken by note inserts — such as `FOR UPDATE` — and re-read the locked state before planning the fold. Cover both ordinary collisions and self-loop folds with concurrent-note regression tests.

### M2 — Reactive compaction forgets tools that already executed successfully

**Location:** `src/main/kotlin/info/skyblond/daapu/agent/persist/PersistChatService.kt:206–224`

**Problem:** `attemptStartSize` is captured before the entire hand run. On `context_exhausted`, `chat.take(attemptStartSize)` removes **all** messages produced by that run, including completed assistant/tool-result rounds.

Context exhaustion commonly occurs on the request following a large tool result. The tool has already executed, but recovery removes its outcome and retries without that knowledge. With bash or write-capable MCP tools, this can repeat side effects.

**Confirmed:** A scripted hand run completed a tool call, returned its successful result, then reported context exhaustion. The retried prompt contained neither the call nor its result.

**Fix:** Preserve completed, paired tool rounds. Discard only the failed round's partial assistant message, then compact the resulting accepted history. Add a regression test proving a successful side-effecting tool is represented in the recovered prompt and is not automatically repeated.

### M3 — Repeated context exhaustion can compact the same prompt forever

**Location:** `src/main/kotlin/info/skyblond/daapu/agent/pipeline/compaction/ChatCompactionService.kt:147–151`
**Related:** `src/main/kotlin/info/skyblond/daapu/agent/persist/PersistChatService.kt:196–255`

**Problem:** After compaction leaves `[summary + N preserved rounds]`, the next compaction again preserves those same N rounds and summarizes only the previous summary.

If the preserved tail, current input, or injection is itself too large, recovery cannot remove the cause. The unbounded outer loop keeps issuing summarization calls and enqueueing extraction jobs. `hand.maxRounds` does not bound this loop because every recovery starts a fresh hand run.

**Confirmed:** Consecutive recovery attempts produced identical prompt content, excluding the regenerated injection.

**Fix:** Make repeated recovery progressively reduce retained history, rather than repeatedly summarizing only the summary. Add a no-progress guard or bounded recovery budget. If mandatory input/injection still cannot fit, terminate with an actionable error instead of continuing indefinitely.

### M4 — Re-embedding can overwrite a newer, correct entity vector

**Location:** `src/main/kotlin/info/skyblond/daapu/memory/eltm/EmbeddingRefreshService.kt:176–207`

**Problem:** Refresh reads an entity's embedding text, releases the transaction, embeds it, and later updates the vector by ID without checking whether the underlying content changed.

Concurrent writes are explicitly allowed after maintenance is disabled, and pre-existing extractions may continue while maintenance is enabled. An attribute update or refinement can therefore commit a correct new vector, only for refresh to overwrite it with a vector derived from older content.

This inconsistency persists after refresh reports success; it is not merely the documented temporary mix of old and new embedding models.

**Confirmed:** Updating an attribute while refresh was paused in its embedding call caused refresh to overwrite the updated vector.

**Fix:** Use optimistic content-version checking. Capture a row revision or content fingerprint, then lock and verify it before writing the vector. Re-embed changed rows rather than overwriting them. Add tests for concurrent attribute updates, refinements, and merges.

### M5 — A stalled stdio MCP handshake can hang beyond its initialization timeout

**Location:** `src/main/kotlin/info/skyblond/daapu/mcp/ClientEntry.kt:111–132, 170–175`

**Problem:** The subprocess is not published into `clientRef` until initialization succeeds. On initialization failure, the SDK calls `close()`, which can wait for a blocking pipe read to finish. The process remains alive, and the application has no independently running cleanup that terminates it.

The normal drop path also closes the client **before** destroying the process, creating the same shutdown-order hazard.

**Confirmed:** With a one-second initialization timeout and a silent subprocess, initialization remained blocked for 60 seconds until the process exited. A second test returned only when an external watcher killed the process.

**Fix:** Give the process an owner immediately after spawning it. A timeout/cancellation watchdog must terminate the process independently of the blocked SDK connect/close operation. During cleanup, terminate the process before awaiting SDK shutdown, with bounded force-kill escalation. Test actual PID termination and elapsed time.

### M6 — Imported-chat validation accepts invalid tool-message ordering

**Location:** `src/main/kotlin/info/skyblond/daapu/agent/chat/ChatCodec.kt:179–202`

**Problem:** Tool validation checks global ID counts, but not chronological execution order. A history such as:

```text
user → tool_result(c) → assistant tool_call(c) → assistant stop
```

passes both completeness validation and the round-locality check used by import.

Strict providers reject the resulting history. The pinned pi-ai transformation also synthesizes another result after the later call, introducing content that was not in the imported conversation.

**Confirmed:** The malformed history passed `validateChat`; the pi-ai transformation added a synthetic tool result.

**Fix:** Validate tool flow in one chronological pass: calls must precede their results, results must answer the pending call batch, and unrelated messages must not interrupt an unfinished batch. Check corresponding tool names as well. Add import/codec tests for reversed, interrupted, and mismatched pairs.

### M7 — A valid MCP tool without a description prevents tool-enabled runs

**Location:** `hand-pi/src/validate.ts:161–162`
**Related:** `src/main/kotlin/info/skyblond/daapu/mcp/ClientEntry.kt:224–227`

**Problem:** MCP tool descriptions are optional. Kotlin deliberately maps an absent description to `""`, but the hand requires every description to be non-blank.

Consequently, one otherwise valid tool causes the per-round tool listing to fail with `tool_transport` before any LLM request. The failure affects every run whose advertised set includes that tool.

**Confirmed:** Passing the Kotlin-produced advertisement shape into `validateTools` rejects it.

**Fix:** Require `description` to be a string, not a non-blank string. Preserve empty descriptions, or omit them when constructing the provider request. Add an integration test covering an MCP tool with no description.

## Minor Issues

### m1 — Filesystem permissions are incorrectly reported on Linux

**Location:** `src/main/kotlin/info/skyblond/daapu/agent/tool/filesystem/FsToolProvider.kt:383–386`

**Problem:** `Files.getAttribute(target, "unix:mode")` returns an `Integer` on Linux, but the code casts it to `Long`. The cast fails, triggering the DOS fallback and potentially reporting `000` rather than the actual permissions.

**Fix:**

```kotlin
val mode = (Files.getAttribute(target, "unix:mode") as? Number)?.toLong()
```

Add a POSIX-backed test asserting the returned permission bits.

## Nits

None. Cosmetic and UX-only findings were intentionally deprioritized.

## Verdict

**Request changes.**

The architecture and existing tests are strong enough for continued PoC development, but the merge data-loss race and compaction recovery problems should be fixed before relying on the memory store or side-effecting tools. The other major findings affect implemented features and are worth addressing in the same correctness pass.
