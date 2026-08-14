# AGENTS.md

Guidance for AI agents (and humans) working in this project.

## Project

PoC of a chatbot with a memory system, built on PostgreSQL and langchain4j.

The project is a Kotlin/JVM application (Gradle) plus a small Svelte frontend.
The pieces:

- **PostgreSQL with pgvector** — accessed through Exposed, schema managed by
  Flyway migrations in `src/main/resources/db/migration/`.
- **langchain4j** — the LLM framework; streaming via
  `OpenAiStreamingChatModel` built per run by `LLM.toStreamingChatModel`
  (`agent/lc4j/llm/LLM.kt`) from the catalog's `LLM` (the non-streaming
  `LLM.toChatModel` serves the one-shots). langchain4j-coupled
  code lives under `agent/lc4j/` (`chat/` converters, `executor/` the
  streaming bridge and round executor, `llm/` the catalog and model
  metadata, `provider/` gateway config, `tool/` the tool-loop seam). The
  conversation-turn machinery is a hand-rolled loop in
  `agent/persist/ChatTurnLoop.kt`.
- **ktor HTTP API** (`server/`) — the input loop: `Main.kt` loads the
  configuration from `config.jsonc` (models in `config/Config.kt`, loaded by
  `loadConfig`), starts the database and the API server. One chat run per
  request: `ChatRunService.prepareRun`
  validates the request (the model is required per message — there is no
  server-side default), `runChat` builds a fresh streaming chat model per
  request (cheap — it holds configuration only, no connections, so
  per-request model selection has no shared state) and runs the turn loop; the
  model catalog, the chat store, and the system prompt are built once and
  shared. Stream progress reaches the client via a `StreamingExecutionCallback`
  implementation that writes SSE events (`server/WebServer.kt`), including
  `tool_result` events emitted when the loop appends locally-executed tool
  results back to the prompt. The SSE stream is flushed immediately with a
  `comment` event before the run starts: ktor Netty's
  `responseWriteTimeoutSeconds` (10s default) starts a timer on the first
  (headers) write and kills the connection if it isn't flushed within 10s,
  which a run that stays silent for minutes during compaction/SSTM extraction
  would otherwise trip (502 at the proxy). Memory CRUD lives in a separate `SstmService`
  (`memory/sstm/SstmService.kt`): an interface with a Postgres implementation
  (`memory/sstm/PostgresSstmService.kt`), shared by the memory CRUD routes and
  the turn loop's context injection. The loop consumes a versioned snapshot
  (`listMemories` → `MemoriesWithVersion`): `chats.sstm_version` stores a
  SHA-256 fingerprint of the `sstms` table (order-sensitive digest shared by
  all implementations via `AbstractSstmService.digestVersion`) captured at the
  last successful run, and the per-turn XML injection's `<sstm-updated>` flag
  is `true` whenever the current fingerprint differs (a fresh chat stores `""`,
  so the first run always flags). Failed runs never reach the store, so the
  stored version stays at the last good state and a change missed by a failed
  run flags on the next success.   `updateMemory` skips the write when the
  content is identical, so no-op edits don't churn the fingerprint.
  Per-chat `Mutex` guards concurrent runs (409), and deleting a chat takes the
  same lock: `chat/PostgresChatStore.store` is an upsert, so deleting mid-run
  would let the in-flight run resurrect the row. Lock entries are created
  atomically with the `tryLock` (`ConcurrentHashMap.compute`) and evicted on
  run completion/delete, so dead chat ids don't accumulate.
- **History compaction and SSTM extraction** (`agent/oneshot/`, wired in
  `agent/persist/ChatTurnLoop.kt`; config under `memory.*` in
  `config.jsonc` — see `config/Config.kt`):
  - Proactive trigger: before the round, when `estimateTokens(chat)` (the
    last assistant message's provider-reported `meta.inputTokens` plus a
    chars/4 estimate for the messages after it) exceeds
    `memory.compactionTriggerFraction × model.contextLength` (default 0.8;
    `0` disables the proactive path). Reactive fallback: EVERY
    `ContextExhausted` round compacts and retries — there is no attempt
    cap, the loop keeps compacting as long as rounds keep exhausting, and
    a compaction that fails or returns a non-clean summary throws and
    fails the run.
  - `ChatCompactor.compactChat(fullChat, excludeLastNRound)` splits at a
    user-turn boundary (never splitting tool_call/tool_result pairs; the
    current run's trailing tool chain always lands in the preserved part),
    feeds the WHOLE chat — drop region, a marker user message ("above are
    the messages to summarize, below are messages for context"), the
    preserved tail, and a final instruction — to a non-streaming one-shot
    call (blocking `chat` on `Dispatchers.IO`) with a dedicated compaction
    system prompt (~500 words target), and replaces the drop region with one
    `CONTEXT COMPACTION: `-marked user message. When the chat has fewer
    rounds than `excludeLastNRound`, the keep count shrinks — down to zero,
    which compacts the entire body — so an over-threshold chat is always
    compacted, even a single overflowing round. The function never returns
    null: it either compacts or throws, leaving the history untouched — a
    chat without user messages throws `IllegalArgumentException` (nothing to
    summarize), a failed/truncated/blank summary throws
    `IllegalStateException`. A prior summary is merged via
    the prompt
    ("The first message might be a summarized message starts with marker
    ..."). A compactor model that cannot see the chat's content (e.g.
    images with a text-only model) fails fast with `ModelCapabilityException`
    (reusing `checkPromptContentCapabilities`) — it is a `memory.compactModel`
    configuration error.
  - `SstmExtractor.processDiscardedMessages(droppedMessages)` runs on the
    raw dropped messages BEFORE they are discarded: the **extractor**
    one-shot call (raw history, attachments included — capability-checked
    via `checkPromptContentCapabilities`, failing fast on a mismatch, a
    `memory.extractModel` configuration error) returns a free-text fact
    list or the `Nothing worth remember.` sentinel (a blank extraction
    also skips the merge); a truncated extraction (non-`stop`
    finish_reason) or one producing tool calls/no text throws and fails
    the run.
    The **merger** is a tool loop (default ≤150 rounds,
    `add/update/delete/list`
    memories over the `sstms` table, ADD/UPDATE/DELETE/NONE semantics). The
    whole merge runs under `ChatRunService`'s `sstmWriteLock`, so a
    concurrent run's injection read never observes a half-merged SSTM.
    Failed merge-round chat calls log and retry indefinitely; a merge
    round ending with a finish reason other than `stop`/`tool_calls`
    throws and fails the run.
    The `sstm-updated` injection flag needs no plumbing: the version digest
    changes when the merge writes, and the loop compares it against
    `chats.sstm_version` (a mid-run reactive compaction regenerates the
    latest user message's injection with the fresh flag + memories).
  - Model resolution: `memory.compactModel/extractModel/mergeModel` are
    catalog ids resolved once at startup (unknown ids and a merge model
    without tool-call support fail fast); `null` falls back to the run's
    model (resolved per run). Compactions emit no dedicated SSE event: the
    frontend resyncs the chat after the run (done/error), which presents the
    compacted history.
- **frontend/** — Svelte 5 + Vite + TypeScript dev server (no build step wired
  into Gradle). It proxies `/api` to the ktor server; ktor serves the API only.
  Chat + memories views; a chat picker dropdown loads a chat on selection
  (re-selecting the same chat acts as a refresh) and is disabled during a run,
  with new/delete buttons; per-message model picker; image attachment via file
  picker/paste.

## Verification commands

```bash
./gradlew test
cd frontend && npm run check && npm run build
```

Run them after any relevant source change. They must exit clean.

## Code quality and style rules

These sections describe the rules/items to watch out when writing or reviewing code.
When writing or reviewing code, looking for bugs with the following perspectives:

+ Bug detection and correctness: Logic errors, off-by-one mistakes, race conditions, unhandled edge cases, incorrect assumptions, regressions.
+ Test coverage and test quality: Coverage gaps, weak assertions, tautological tests, missing scenarios. Are key code paths tested? Do tests actually validate correct behavior? Are unit tests well-structured with meaningful assertions?
+ Performance and security: Inefficiencies, resource leaks, injection risks, insecure defaults, exposed secrets, missing input validation.
+ Code quality and style: follow existing pattern (project conventions), no dark magic, no hacky solution/workaround, no complex logic without comments. Maintainability is the first priority.
+ Config models and their schema: `config.schema.json` mirrors the config
  models in `config/Config.kt` (and the checked-in `config.example.jsonc`
  documents the shape). Treat the schema as documentation: when the config
  models change — new fields, renames, defaults, validation rules — update
  `config.schema.json` (and `config.example.jsonc` if the example changes)
  in the same change. Reviews must check the schema and the models match.

## Backend style

- Coroutine-native: DB access goes through Exposed `suspendTransaction` wrapped
  in `withContext(Dispatchers.IO)`; never call blocking JDBC on the event loop.
- Never log secrets (passwords, API keys, session cookies).
- The streaming bridge between langchain4j's callback-based handler and the
  coroutine turn loop lives in `agent/lc4j/executor/StreamSignal.kt` as a
  `channelFlow` of `StreamSignal`s — keep langchain4j-coupled code inside
  `agent/lc4j/`; the loop only consumes the flow and the neutral history
  DTOs.
- Prefer fail-fast design

## Operational rule

+ DO NOT touch git after making changes. User should review the change and manually stage the changes.
