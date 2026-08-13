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
  (`agent/lc4j/llm/LLM.kt`) from the catalog's `LLM`. langchain4j-coupled
  code lives under `agent/lc4j/` (`chat/` converters, `executor/` the
  streaming bridge and round executor, `llm/` the catalog and model
  metadata, `provider/` gateway config, `tool/` the tool-loop seam). The
  conversation-turn machinery is a hand-rolled loop in `agent/ChatTurnLoop.kt`.
- **ktor HTTP API** (`server/`) — the input loop: `Main.kt` starts the database
  and the API server. One chat run per request: `ChatRunService.prepareRun`
  validates the request (the model is required per message — there is no
  server-side default), `runChat` builds a fresh streaming chat model per
  request (cheap — it holds configuration only, no connections, so
  per-request model selection has no shared state) and runs the turn loop; the
  model catalog, the chat store, and the system prompt are built once and
  shared. Stream progress reaches the client via a `StreamingExecutionCallback`
  implementation that writes SSE events (`server/WebServer.kt`), including
  `tool_result` events emitted when the loop appends locally-executed tool
  results back to the prompt. Memory CRUD lives in a separate `SstmService`
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
  run flags on the next success. `updateMemory` skips the write when the
  content is identical, so no-op edits don't churn the fingerprint.
  Per-chat `Mutex` guards concurrent runs (409), and deleting a chat takes the
  same lock: `chat/PostgresChatStore.store` is an upsert, so deleting mid-run
  would let the in-flight run resurrect the row. Lock entries are created
  atomically with the `tryLock` (`ConcurrentHashMap.compute`) and evicted on
  run completion/delete, so dead chat ids don't accumulate.
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

## Backend style

- Coroutine-native: DB access goes through Exposed `suspendTransaction` wrapped
  in `withContext(Dispatchers.IO)`; never call blocking JDBC on the event loop.
- Never log secrets (passwords, API keys, session cookies).
- The streaming bridge between langchain4j's callback-based handler and the
  coroutine turn loop lives in `agent/lc4j/executor/StreamSignal.kt` as a
  `channelFlow` of `StreamSignal`s — keep langchain4j-coupled code inside
  `agent/lc4j/`; the loop only consumes the flow and the neutral history
  DTOs.
