# AGENTS.md

Guidance for AI agents (and humans) working in this project.

## Migration in progress: koog → langchain4j

The project is migrating off koog onto langchain4j (with its `langchain4j-mcp`
module). **Status: #8 done — the runtime (API, turn loop, history, MCP tools)
runs on langchain4j, the last koog client code is gone, and only the koog
dependency remains (#9).** The work is tracked as GitHub issues with blocking
relationships (`gh issue list`, `gh issue view N`):

- Spikes first: #1 (streaming/reasoning parity against the live gateways —
  **GO**), #2 (mid-stream SSE error-chunk classification — **GO**, the
  go/no-go unknown), #3 (MCP tools end-to-end). Spike code lives on throwaway
  branches; outcomes are recorded as comments on the issue.
- #4 (**Done**): neutral chat-history format, decoupling the DB/API/frontend
  from koog's serialization (see "Chat history is turn-loop-managed" below).
- #5 (**Done**): the langchain4j model catalog (`langchain4j/ModelCatalog.kt`,
  `ModelMetadata`, `StreamingChatModelFactory`, framework-agnostic
  `checkPromptContentCapabilities`) with tests; the koog catalog was deleted
  when #6 switched the runtime over. `ModelCatalogTest` pins the entries by
  value. Builder knobs are pinned by the #1 spike (see
  `StreamingChatModelFactory.kt`'s KDoc for the deferred caveats: truncation
  via `finishReason()==null` and the Novita inline-`<think>` load-balancing
  quirk; the Cerebras `delta.reasoning` dialect gap was closed in #6 by
  `ReasoningRewriteHttpClient`).
- #6 (**Done**): the core turn loop (`agent/ChatTurnLoop.kt`) — see
  "Streaming execution and recovery" below. The koog strategy graph
  (`agent/ChatAgentFactory.kt`), koog `ChatMemory`/`ChatHistoryProvider`,
  `KoogHistoryConverters`, and the koog catalog are deleted. #6 also landed
  the #1 spike's deferred reasoning decorator
  (`langchain4j/ReasoningDialect.kt`: rewrites the gateway's `delta.reasoning`
  dialect to `reasoning_content` at the HTTP-SSE layer, so reasoning streams
  live and round-trips via `sendThinking`).
- #7 (**Done**): `CustomOpenAILLMClient` and `koog/Utils.kt` (and their
  tests) are deleted; the gateway-quirk test suite was re-expressed against
  the langchain4j stack as `langchain4j/GatewayQuirkParityTest.kt` (the
  quirk matrix: reasoning dialects, empty deltas, tool-call assembly,
  usage, error chunks, HTTP status preservation — see its KDoc for the
  parity verdict per quirk; `reasoning_details` is consciously dropped, the
  `withGeneratedToolCallIds` sanitizer's end-to-end guarantee is pinned by
  `ChatTurnLoopTest`'s id-less tool-call test).
- #8 (**Done**): MCP tools. `mcp/McpToolProvider.kt` implements the
  `agent/ToolProvider.kt` seam against `langchain4j-mcp:1.18.1-beta28`:
  per-server config hardcoded in `Main.kt` (PoC choice — only API keys come
  from env/`.env`, e.g. the exa server's `EXA_API_KEY`), lazy connect +
  cached clients (per-request runs share them; a connect failure skips only
  that server with a 30s retry cooldown), tool names advertised as
  `{server}_{tool}` (unique `tools` arrays), error policy (server-side
  `isError` → error tool-result so the model can react; transport failures —
  connect refused, stdio process death — drop the client, fail the run with
  a clear SSE `error` event, and reconnect on the next run), graceful close
  via a JVM shutdown hook in `startWebServer`. Pinned by `McpToolProviderTest`
  (mock streamable-HTTP + stdio subprocess servers) and
  `ChatTurnLoopTest`'s end-to-end MCP tool round. Tools in history were
  already handled: the neutral `tool_call`/`tool_result` id pairing landed
  with #6.
- Remaining: #9 (remove koog, final cleanup + docs).

Why: koog's fix turnaround for OpenAI-compatible gateway quirks is too slow
for this project (e.g. reasoning silently dropped in streaming,
JetBrains/koog#2148 — we carried a ~350-line client subclass patching such
bugs), its MCP module is tools-only, and it has no pgvector/RAG ecosystem for
the planned long-term-memory experiments. langchain4j covers all three and is
considerably more active.

Rules while the migration is underway:

- Pick up issues in dependency order; each issue body is self-contained
  (context, file paths, checklists, acceptance criteria).
- Do NOT add new koog API surface beyond what an issue explicitly asks for.
  New LLM-facing code follows the langchain4j target design. The only koog
  code still alive is `llm/FlagTool.kt` (koog-typed example tool, #8/#9).
- The behaviors documented in "Chat history is turn-loop-managed" and
  "Streaming execution and recovery" below are **invariants**, not koog
  accidents — preserved by the langchain4j port (fail-fast history loads,
  never-store-history-on-failure, retry classification, full-prompt
  capability checks, injection strip, SSE protocol byte-compatibility, ...).

## Project

PoC of a chatbot with a memory system, built on PostgreSQL and langchain4j.

The project is a Kotlin/JVM application (Gradle) plus a small Svelte frontend.
The pieces:

- **PostgreSQL with pgvector** — accessed through Exposed, schema managed by
  Flyway migrations in `src/main/resources/db/migration/`.
- **langchain4j** — the LLM framework; streaming via
  `OpenAiStreamingChatModel` built per run by `langchain4j/StreamingChatModelFactory.kt`
  from the catalog's `ModelMetadata`. The conversation-turn machinery is a
  hand-rolled loop in `agent/ChatTurnLoop.kt` (the old koog strategy graph was
  custom logic wearing a DSL costume).
- **ktor HTTP API** (`server/`) — the input loop: `Main.kt` starts the database
  and the API server. One chat run per request: `ChatRunService.prepareRun`
  validates the request (the model is required per message — there is no
  server-side default), `runChat` builds a fresh streaming chat model per
  request (cheap — it holds configuration only, no connections, so
  per-request model selection has no shared state) and runs the turn loop; the
  model catalog, the history store, and the system prompt are built once and
  shared. Stream progress reaches the client via a `StreamExecutionCallback`
  implementation that writes SSE events (`server/WebServer.kt`), including
  `tool_result` events emitted when the loop appends locally-executed tool
  results back to the prompt. Memory CRUD lives in a separate `SstmService`
  (the turn loop's context injection reads the `sstms` table directly).
  Per-chat `Mutex` guards concurrent runs (409), and deleting a chat takes the
  same lock: `PostgresHistoryStore.store` is an upsert, so deleting mid-run
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
  coroutine turn loop lives in `langchain4j/StreamingSignals.kt` as a
  `channelFlow` of `StreamSignal`s — keep langchain4j-coupled code out of the
  `agent/` package; the loop only consumes the flow and the neutral history
  DTOs.

## Chat history is turn-loop-managed, stored in a project-owned format

The turn loop owns the conversation history lifecycle (not hand-rolled message
tables), and the serialized format is ours:

- `agent/ChatTurnLoop.kt` loads the full neutral history before a run
  (`history/PostgresHistoryStore.kt`) and stores the updated conversation
  (including the new user message and assistant reply, plus any tool rounds)
  after the run **succeeds**. A failed or aborted run never reaches the store,
  so history stays at the last good state.
- `chats.history_json` stores a **framework-neutral** JSON array
  (`history/HistoryMessage.kt`): roles system/user/assistant/tool, parts
  text / reasoning / tool_call / tool_result / attachment, plus per-message
  meta (timestamp, token usage) and `finishReason`. No koog or langchain4j
  type names cross the DB or the API boundary (`GET /api/chats/{id}/history`
  serves this format).
- The neutral list is the canonical in-loop structure: each round builds its
  request from a fresh conversion (`langchain4j/Langchain4jHistoryConverters.kt`
  — neutral ↔ langchain4j `ChatMessage`s; see the mapping notes in
  `history/HistoryMessage.kt`'s KDoc), and accepted responses are captured
  back into neutral messages with their `ChatResponse` metadata (token usage,
  `finishReason` wire name) at accept time.
- Loading fails fast: an undecodable `history_json` (corrupt row, or an
  incompatible format change) throws instead of silently resetting the chat
  to empty. The golden-JSON tests in `HistoryCodecTest` pin the neutral format
  and `Langchain4jHistoryConvertersTest` pins the neutral↔langchain4j mapping,
  so a breaking change to either fails in tests first.
- Existing koog-format rows were **discarded** when the neutral format landed
  (issue #4 decision for this PoC: fresh DB, no migration path).

When adding chat features, manipulate history via the turn loop and the
neutral DTOs rather than inserting message rows directly.

## Streaming execution and recovery

The turn loop (`agent/ChatTurnLoop.kt`) streams each LLM round through
`langchain4j/StreamingSignals.kt` (a `channelFlow` bridging langchain4j's
callback-based `StreamingChatResponseHandler` — the first terminal signal
wins, and cancelling the flow aborts the in-flight HTTP request via the
`StreamingHandle` captured from the context-carrying callback variants) and
classifies the result (`classifyStreamResult` in
`agent/StreamExecutionResult.kt`) before accepting it:

- Transient hiccups (5xx, connection drops, malformed streams, empty responses
  with no reason) retry forever with exponential backoff; permanent 4xx fail
  the run. Some gateways deliver errors as a mid-stream SSE `{"error": ...}`
  chunk instead of an HTTP error status; langchain4j completes such a stream
  **normally** with `finishReason() == null`, so the loop scans the raw SSE
  events (`langchain4j/ErrorChunkScan.kt`) **before** any acceptance check:
  a numeric `code` (OpenRouter-style, e.g. a moderation rejection mapped to
  403) is thrown as `dev.langchain4j.exception.HttpException(code, data)`, a
  code-less chunk as `MidStreamErrorChunkException` (transient).
  `isRetryableStreamError` walks the exception cause chain for the first
  `HttpException` with a non-2xx status to classify it: a permanent code fails
  the run instead of retrying forever, a transient one (408/429) is retried,
  a 2xx or no code is transient (the 2xx is the stream's own HTTP status,
  never a meaningful error code). Reasoning deltas arriving in the gateway's
  native dialect (`delta.reasoning` plain text, via the bifrost proxy) are
  rewritten to `reasoning_content` at the HTTP-SSE layer by
  `langchain4j/ReasoningDialect.kt`, so the stock parser accumulates
  `AiMessage.thinking()` and reasoning streams live. An empty, blank, or
  reasoning-only response carrying a *named* `finish_reason` (e.g.
  `content_filter`, or a deterministic empty `stop`) is definitive — the
  provider ended it on purpose — so it fails the run with
  `EmptyPermanentResponseException` instead of retrying forever; only an
  empty response with NO reason is treated as transient. A stream that ends
  without `finish_reason` is treated as truncated: langchain4j has no
  `requireEndFrame` equivalent and silently accepts clean EOF, so the loop
  throws `EmptyStreamResponseException` itself when `finishReason() == null`
  (unknown/custom finish reasons like `model_length` map to null too, landing
  in the same retryable bucket).
  Deterministic guard failures (`check`/`error`, `IllegalStateException`) are
  NOT retried — the identical request would fail identically forever. JVM
  `Error`s (OOM, stack overflow) are NOT retried either — they would likely
  recur and impede GC recovery, so crashing is more recoverable than an
  infinite retry loop. `IllegalArgumentException` IS retried on purpose: LLM
  output is stochastic, so e.g. malformed tool-call argument JSON can parse
  fine on a fresh attempt. The retry policy lives in `isRetryableStreamError`
  (`agent/StreamExecutionResult.kt`) and is pinned by
  `IsRetryableStreamErrorTest`; the loop's round behavior is pinned end-to-end
  by `ChatTurnLoopTest` (mock SSE server, no gateways).
- Model-capability violations (e.g. images with a text-only model) are caught
  in the loop's pre-send step by `checkPromptContentCapabilities`
  (`langchain4j/ModelCapabilityCheck.kt`), which scans the FULL prompt
  (loaded history + new input — images can come from either: send an image
  with a vision model, then switch the chat to a text-only model, and the
  image re-enters the prompt from history) and throws `ModelCapabilityException`
  BEFORE any LLM request. This must not be validated in the HTTP layer only
  (it would miss history). The dedicated type is pinned as non-retryable in
  `isRetryableStreamError`; the API layer accepts images with any model and
  lets the loop fail with a clear SSE `error` event instead.
- A response is only accepted if it has non-blank text or tool-call parts — an
  empty, blank, or reasoning-only message would be stored as `content: null`
  or empty content, and strict providers would reject every later run with a
  400, bricking the chat. (langchain4j skips empty `content` deltas too:
  OpenAI-style streams open with `{"delta":{"role":"assistant","content":""}}`.)
  Accepted messages DO keep their reasoning part in stored history on purpose
  (reasoning stays visible in history for debugging); langchain4j's
  `sendThinking(true)` (enabled per-model by the factory) re-sends it as
  `reasoning_content` on later requests. That works with the current gateway,
  but a provider that rejects the field would 400 every later run of that
  chat — strip reasoning in the loop's pre-send step if that ever happens.
- `finish_reason == "length"` means the *output* budget ran out. Since input
  and output share the context window, compaction only helps when the prompt
  crowds it (`promptTokens > contextLength - maxOutputTokens`): those cases
  fail the run with a descriptive `IllegalStateException` (history compaction
  is a TODO, so context exhaustion is unrecoverable for now). At or below the
  threshold the output cap bound on its own (e.g. reasoning burned the whole
  budget), so the run fails fast with `OutputExhaustionException` — the fix is
  the user's choice (different model, bigger output limit, lower thinking
  budget). When the provider sends no usage data, classification cannot tell
  which limit bound, so it also fails fast with `OutputExhaustionException`
  rather than retrying or compacting blindly.
- The system prompt is refreshed in place each run (only a system message at
  index 0 is kept and its text updated; a missing one is inserted), and the
  per-turn XML injection is prepended to the new user message and stripped
  from the latest user message after the round (identified by XSD validation,
  see `agent/ContextInjection.kt`).
- Tool rounds: an accepted response with tool calls executes them through
  `agent/ToolProvider.kt` (the MCP implementation `mcp/McpToolProvider.kt` is
  used whenever `Main.kt` hardcodes MCP servers — currently the exa search
  server; `EmptyToolProvider` — no tools advertised, any call answered with
  an explicit error result — remains the no-MCP fallback), appends the
  results as `tool` messages, and starts the next round. Tool execution
  happens OUTSIDE the streaming retry loop: a tool-level failure (server-side
  `isError`, bad arguments) becomes an error tool-result for the model, while
  a transport failure (`McpTransportException`) fails the run immediately
  with a clear SSE `error` event — no retry of the whole LLM round.
- **Parallel tool calls in ONE streaming round are fragile upstream.** The
  OpenAI streaming parser (langchain4j 1.18.1) accumulates tool-call chunks
  in ONE shared `ToolCallBuilder` and flushes it as "complete" whenever the
  chunk's `index` differs from the current one. Chunks must therefore arrive
  index-sequential (`idx0` id/name/args fully before `idx1`); a gateway that
  interleaves indexes (`idx0, idx1, idx0, idx1`) flushes partial calls (args
  defaulted to `{}`, later ones with null id/name), corrupting or dropping the
  calls — `ChatTurnLoopTest`'s parallel-tool-call test pins the expected
  sequential wire order. Known upstream bug class: langchain4j#4889/#4921
  (final response drops tool calls the stream already emitted), #4937
  (interleaved blocks corrupt the Anthropic client — same shape), #4528/#4544
  (DeepSeek/Qwen repeat the full tool-call id every chunk — fixed via
  `accumulateToolCallId(false)` builder flag, default true),
  #4900 (Claude-via-OpenAI-proxy: two calls merged). Now that #8 landed real
  tools, investigate a "parallel calls never execute / args corrupted /
  tool-call id duplicated" report by capturing the raw SSE chunks for the
  failing round: check chunk order per `index` (interleaved?) and whether ids
  repeat per chunk (DeepSeek-family). Fixes in order of preference: upstream
  fix/version bump (per-index builder map, cf. #4937),
  `accumulateToolCallId` per-model knob in `StreamingChatModelFactory` for
  id-repeat gateways, or a local patch in the style of
  `ReasoningDialect`/the old `CustomOpenAILLMClient`.
  Note: none of this affects the round-level agentic interleave (think →
  think → tool → conclude), which is ordinary multi-round loop behavior —
  within ONE OpenAI-protocol response, reasoning deltas always come before
  the terminal tool-call chunks; "thinking again" happens on the next round
  via `sendThinking`.
- If the SSE client disconnects mid-run, writing the stream fails: the sink
  wrapper in `WebServer.kt` converts it to `CancellationException` (pinned
  non-retryable), aborting the run instead of letting the retry loop treat a
  closed channel as a transient stream error and burn tokens forever.
