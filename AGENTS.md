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
  model catalog, the history store, and the system prompt are built once and
  shared. Stream progress reaches the client via a `StreamingExecutionCallback`
  implementation that writes SSE events (`server/WebServer.kt`), including
  `tool_result` events emitted when the loop appends locally-executed tool
  results back to the prompt. Memory CRUD lives in a separate `SstmService`
  (the turn loop's context injection reads the `sstms` table directly).
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

## Chat history is turn-loop-managed, stored in a project-owned format

The turn loop owns the conversation history lifecycle (not hand-rolled message
tables), and the serialized format is ours:

- `agent/ChatTurnLoop.kt` loads the full neutral history before a run
  (`chat/PostgresChatStore.kt`) and stores the updated conversation
  (including the new user message and assistant reply, plus any tool rounds)
  after the run **succeeds**. A failed or aborted run never reaches the store,
  so history stays at the last good state.
- `chats.history_json` stores a **framework-neutral** JSON array
  (`chat/ChatMessage.kt`): roles system/user/assistant/tool_result, parts
  text / reasoning / tool_call / tool_result / attachment, plus per-message
  meta (timestamp, token usage) and `finishReason`. No framework type names
  cross the DB or the API boundary (`GET /api/chats/{id}/history`
  serves this format).
- The neutral list is the canonical in-loop structure: each round builds its
  request from a fresh conversion (`agent/lc4j/chat/Lc4jChatMessageConverters.kt`
  — neutral ↔ langchain4j `ChatMessage`s; see the mapping notes in
  `chat/ChatMessage.kt`'s KDoc), and accepted responses are captured
  back into neutral messages with their `ChatResponse` metadata (token usage,
  `finishReason` wire name) at accept time.
- Loading fails fast: an undecodable `history_json` (corrupt row, or an
  incompatible format change) throws instead of silently resetting the chat
  to empty. The golden-JSON tests in `ChatCodecTest` pin the neutral format
  and `Langchain4jHistoryConvertersTest` pins the neutral↔langchain4j mapping,
  so a breaking change to either fails in tests first.
- History rows in any pre-neutral format were **discarded**, not converted
  (fresh-DB PoC decision): an old `history_json` row fails the fast load
  rather than being silently reset.

When adding chat features, manipulate history via the turn loop and the
neutral DTOs rather than inserting message rows directly.

## Streaming execution and recovery

The turn loop (`agent/ChatTurnLoop.kt`) streams each LLM round through
`agent/lc4j/executor/StreamSignal.kt` (a `channelFlow` bridging langchain4j's
callback-based `StreamingChatResponseHandler` — the first terminal signal
wins, and cancelling the flow aborts the in-flight HTTP request via the
`StreamingHandle` captured from the context-carrying callback variants and
cancelled in `awaitClose`). `Lc4jStreamingExecutor.executeOnce`
(`agent/lc4j/executor/Lc4jStreamingExecutor.kt`) classifies the outcome into
a `StreamingExecutionResult` before the loop accepts it:

- The round is retried ONLY when the stream completed cleanly with no usable
  output and NO `finish_reason` (`EmptyTransient`, backoff
  100ms→6.4s). Everything else fails the run immediately with a clear SSE
  `error` event: HTTP-level failures (5xx, 408/429, connection drops) throw
  from the executor and are NOT retried, and a mid-stream SSE `{"error": ...}`
  chunk (OpenRouter-style, e.g. a moderation rejection mapped to 403) is
  thrown by `Lc4jStreamingExecutor.findErrorChunk` **before** any acceptance
  check — a numeric `code` as `dev.langchain4j.exception.HttpException(code,
  data)`, a code-less chunk as `MidStreamErrorChunkException`. (The old
  exception-based retry policy with permanent-4xx classification was removed
  in the executor refactor for simplicity; classifying HTTP failures back
  into the transient bucket, e.g. 429 rate limits, is a TODO.) Reasoning
  deltas arriving in the gateway's native dialect (`delta.reasoning` plain
  text, via the bifrost proxy) are rewritten to `reasoning_content` at the
  HTTP-SSE layer by `agent/lc4j/provider/client/ReasoningRewriteHttpClient.kt`,
  so the stock parser accumulates `AiMessage.thinking()` and reasoning streams
  live.
- An empty, blank, or reasoning-only response carrying a *named* `finish_reason`
  (e.g. `content_filter`, a deterministic empty `stop`, or any unknown
  reason) is definitive — the provider ended it on purpose — so the loop
  fails the run with `IllegalStateException` instead of retrying forever;
  only an empty response with NO reason is treated as transient. A stream
  that ends without `finish_reason` is treated as truncated: langchain4j has
  no `requireEndFrame` equivalent and silently accepts clean EOF, so
  `finishReason() == null` lands in the same retryable `EmptyTransient`
  bucket (unknown/custom finish reasons like `model_length` map to null too).
- Model-capability violations (e.g. images with a text-only model) are caught
  in the loop's pre-send step by `checkPromptContentCapabilities`
  (`agent/ChatTurnLoop.kt`), which runs **per round** against the current
  prompt (loaded history + new input + any tool results of this run — images
  can come from all three: send an image with a vision model, then switch the
  chat to a text-only model and the image re-enters the prompt from history;
  an MCP tool can return an image mid-run, and the check descends into
  `tool_result` parts) and throws `ModelCapabilityException`
  BEFORE any LLM request. This must not be validated in the HTTP layer only
  (it would miss history). The API layer accepts images with any model and
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
- `finish_reason == "length"` means the *output* budget ran out; the response
  is never accepted (a truncated answer is not worth storing — a chat must
  end with a clean `stop`, see `ChatCodec.validateChat`). Since input
  and output share the context window, compaction only helps when the prompt
  crowds it (`promptTokens > contextLength - maxOutputTokens`): those cases
  surface as `ContextExhausted` (history compaction is a TODO, so context
  exhaustion is unrecoverable for now). At or below the threshold the output
  cap bound on its own (e.g. reasoning burned the whole budget), so the run
  fails fast with `OutputBudgetExhausted` — the fix is
  the user's choice (different model, bigger output limit, lower thinking
  budget). When the provider sends no usage data, classification cannot tell
  which limit bound, so it also fails with `OutputBudgetExhausted`
  rather than retrying or compacting blindly.
- The system prompt is refreshed in place each run (only a system message at
  index 0 is kept and its text updated; a missing one is inserted), and the
  per-turn XML injection is prepended to the new user message and stripped
  from the latest user message after the round (identified by XSD validation,
  see `agent/ContextInjection.kt`).
- Tool rounds: an accepted response with tool calls executes them through
  `agent/lc4j/tool/ToolProvider.kt` (the MCP implementation `mcp/McpToolProvider.kt` is
  used whenever `Main.kt` hardcodes MCP servers — currently the exa search
  server; `EmptyToolProvider` — no tools advertised, any call answered with
  an explicit error result — remains the no-MCP fallback), appends the
  results as `tool_result` messages, and starts the next round. Tool execution
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
  `accumulateToolCallId` per-model knob in `LLM.toStreamingChatModel` for
  id-repeat gateways, or a local patch in the style of
  `ReasoningRewriteHttpClient`/the old `CustomOpenAILLMClient`.
  Note: none of this affects the round-level agentic interleave (think →
  think → tool → conclude), which is ordinary multi-round loop behavior —
  within ONE OpenAI-protocol response, reasoning deltas always come before
  the terminal tool-call chunks; "thinking again" happens on the next round
  via `sendThinking`.
- If the SSE client disconnects mid-run, writing the stream fails: the sink
  wrapper in `WebServer.kt` converts it to `CancellationException` (pinned
  non-retryable), aborting the run instead of letting the retry loop treat a
  closed channel as a transient stream error and burn tokens forever.
