# AGENTS.md

Guidance for AI agents (and humans) working in this project.

## Project

PoC of a chatbot with a memory system, built on PostgreSQL and koog.

The project is a Kotlin/JVM application (Gradle) plus a small Svelte frontend.
The pieces:

- **PostgreSQL with pgvector** — accessed through Exposed, schema managed by
  Flyway migrations in `src/main/resources/db/migration/`.
- **koog** — the LLM agent framework; its `ChatMemory` feature owns the
  conversation history.
- **ktor HTTP API** (`server/`) — the input loop: `Main.kt` starts the database
  and the API server. One chat run per request: `ChatRunService.prepareRun`
  validates the request (the model is required per message — there is no
  server-side default), `buildChatAgent` (`agent/ChatAgentFactory.kt`) builds a
  fresh agent (cheap — koog's `close()` is a no-op and each `run()` gets its own
  session context, so a per-request agent gives per-request model/callback
  selection with no shared state; the LLM executor, history provider, and
  system prompt are built once and shared). Stream progress reaches the client
  via a `StreamExecutionCallback` implementation that writes SSE events
  (`server/WebServer.kt`), including `tool_result` events emitted when the
  agent appends locally-executed tool results back to the prompt. Memory CRUD
  lives in a separate `SstmService` (the agent's context injection reads the
  `sstms` table directly). Per-chat `Mutex` guards concurrent runs (409), and
  deleting a chat takes the same lock: `PostgresChatHistoryProvider.store` is
  an upsert, so deleting mid-run would let the in-flight run resurrect the row.
  Lock entries are created atomically with the `tryLock` (`ConcurrentHashMap.compute`)
  and evicted on run completion/delete, so dead chat ids don't accumulate.
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
- The model catalog (`koog/client/ModelCatalog.kt`) lives in its own file, NOT
  in `LLMs.kt`: a catalog val in the same class as `createModel` would create a
  JVM class-init cycle (the catalog reads the `Cerebras`/`Novita` object fields
  while those objects' init calls `createModel` back into the same class),
  silently leaving catalog entries null.

## Chat history is koog-managed

The conversation history is owned by koog's `ChatMemory` feature, not by
hand-rolled message tables:

- A koog `AIAgent` with `ChatMemory` installed + chat history provider allows the agent 
  to load chat history before each run and stores the updated conversation (including the
  new user message and assistant reply) after.
- `PostgresChatHistoryProvider` implements koog's `ChatHistoryProvider`: it
  serializes the full koog `Message` list into the chat row's `history_json`
  column as one JSON array. The key is koog's opaque conversation id (its
  `runId`, i.e. the `sessionId` passed to `AIAgent.run`), stored verbatim as
  the `chats.id` primary key.
- Loading fails fast: an undecodable `history_json` (corrupt row, or a koog
  upgrade that changed the `Message` format) throws instead of silently
  resetting the chat to empty. The golden-JSON tests in
  `PostgresChatHistoryProviderTest` pin the current serialization format so a
  breaking koog upgrade fails in tests first.

When adding chat features, manipulate history via koog (its `ChatHistoryProvider`
and features) rather than inserting message rows directly.

## Streaming execution and recovery

The agent's strategy (`agent/ChatAgentFactory.kt`) streams each LLM round and
classifies the result (`classifyStreamResult`) before accepting it:

- Transient hiccups (5xx, connection drops, malformed streams, empty responses
  with no reason) retry forever with exponential backoff; permanent 4xx fail
  the run. Some gateways deliver errors as a mid-stream SSE `{"error": ...}`
  chunk instead of an HTTP error status; when the chunk carries a numeric
  `code` (OpenRouter-style, e.g. a moderation rejection mapped to 403),
  `CustomOpenAILLMClient` surfaces it as the exception's status code. ktor's
  SSE plugin and koog's SSE wrapper both re-wrap the exception (with the
  stream's own 2xx response status on top), so `isRetryableStreamError` walks
  the cause chain for the first non-2xx status to classify it: a permanent
  code fails the run instead of retrying forever, a transient one (408/429)
  is retried. An empty, blank, or reasoning-only response carrying a *named*
  `finish_reason` (e.g. `content_filter`, or a deterministic empty `stop`) is
  definitive — the provider ended it on purpose — so it fails the run with
  `EmptyPermanentResponseException` instead of retrying forever; only an empty
  response with NO reason is treated as transient. A stream that ends without
  `finish_reason` is treated as truncated:
  the client only emits the terminal `End` frame when the gateway sent
  `finish_reason`, so koog's `requireEndFrame` flags the incomplete stream and
  it is retried. Deterministic guard failures (`check`/`error`,
  `IllegalStateException`) are NOT retried — the identical request would fail
  identically forever. JVM `Error`s (OOM, stack overflow) are NOT retried
  either — they would likely recur and impede GC recovery, so crashing is more
  recoverable than an infinite retry loop. `IllegalArgumentException` IS
  retried on purpose: LLM
  output is stochastic, so e.g. malformed tool-call argument JSON can parse
  fine on a fresh attempt. The retry policy lives in `isRetryableStreamError`
  (`agent/StreamExecutionResult.kt`) and is pinned by
  `IsRetryableStreamErrorTest`. A failed run never reaches `ChatMemory.store`,
  so history stays at the last good state.
- Model-capability violations (e.g. images with a text-only model) are caught
  in `userInputPreprocess` by `checkPromptContentCapabilities`
  (`agent/ModelCapabilityCheck.kt`), which scans the FULL prompt (loaded
  history + new input — images can come from either: send an image with a
  vision model, then switch the chat to a text-only model, and the image
  re-enters the prompt from history) and throws `ModelCapabilityException`
  BEFORE any LLM request. This must not be validated in the HTTP layer only
  (it would miss history) and cannot be keyed on koog's own exception:
  koog's `requireCapability` is a bare `require(...)`, i.e.
  `IllegalArgumentException`, which the policy deliberately retries. So the
  dedicated type is pinned as non-retryable in `isRetryableStreamError` and
  thrown from the preprocess node; the API layer accepts images with any
  model and lets the strategy fail with a clear SSE `error` event instead.
- A response is only accepted if it has non-blank text or tool-call parts — an
  empty, blank, or reasoning-only message would be stored as `content: null`
  or empty content, and strict providers would reject every later run with a
  400, bricking the chat. (The client also skips empty `content` deltas:
  OpenAI-style streams open with `{"delta":{"role":"assistant","content":""}}`.)
  Accepted messages DO keep their `MessagePart.Reasoning` part on purpose
  (reasoning stays visible in history for debugging); koog re-sends it as
  `reasoning_content` on later requests. That works with the current gateway,
  but a provider that rejects the field would 400 every later run of that
  chat — strip reasoning in `userInputPreprocess` if that ever happens.
- `finish_reason == "length"` means the *output* budget ran out. Since input
  and output share the context window, compaction only helps when the prompt
  crowds it (`promptTokens > contextLength - maxOutputTokens`): those cases
  route to the `exhaustionCompact` node, which currently fails the run with a
  descriptive `IllegalStateException` (history compaction is a TODO, so
  context exhaustion is unrecoverable for now). At or below the threshold the
  output cap bound on its own (e.g. reasoning burned the whole budget), so the
  run fails fast with `OutputExhaustionException` — the fix is the user's
  choice (different model, bigger output limit, lower thinking budget). When
  the provider sends no usage data, classification cannot tell which limit
  bound, so it also fails fast with `OutputExhaustionException` rather than
  retrying or compacting blindly.
- If the SSE client disconnects mid-run, writing the stream fails: the sink
  wrapper in `WebServer.kt` converts it to `CancellationException` (pinned
  non-retryable), aborting the run instead of letting the retry loop treat a
  closed channel as a transient stream error and burn tokens forever.
