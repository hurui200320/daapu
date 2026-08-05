# AGENTS.md

Guidance for AI agents (and humans) working in this project.

## Project

PoC of a chatbot with a memory system, built on PostgreSQL and koog.

The project is a Kotlin/JVM application (Gradle). The two pieces of
infrastructure:

- **PostgreSQL with pgvector** — accessed through Exposed, schema managed by
  Flyway migrations in `src/main/resources/db/migration/`.
- **koog** — the LLM agent framework; its `ChatMemory` feature owns the
  conversation history.

The web server, HTTP API, and frontend were removed as LLM-generated boilerplate
that didn't fit the project's idea; the PoC is built around the koog + Postgres
pipeline only. `Main.kt` runs a single agent turn with a hardcoded debug
message and a hardcoded session id (so the same chat is resumed across runs);
a real input loop comes later.

## Verification commands

```bash
./gradlew test
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

The agent's strategy (`Main.kt`) streams each LLM round and classifies the
result (`classifyStreamResult`) before accepting it:

- Transient hiccups (5xx, connection drops, malformed streams, empty responses
  with no reason) retry forever with exponential backoff; permanent 4xx fail
  the run. An empty, blank, or reasoning-only response carrying a *named*
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
