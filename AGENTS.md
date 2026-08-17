# AGENTS.md

Guidance for AI agents (and humans) working in this project.

## Project

PoC of a chatbot with a memory system: Kotlin/JVM (Gradle) brain + Svelte
frontend + Node/TS "hand-pi" service.

- **PostgreSQL + pgvector** — Exposed access, Flyway schema in
  `src/main/resources/db/migration/`.
- **hand-pi** (`hand-pi/`, `@earendil-works/pi-ai` pinned 0.84.1) — stateless,
  opinionless LLM *execution*: streaming, dialects, tool-call accumulation,
  retries, usage. No catalog/sessions/prompts; everything arrives per request.
  Kotlin owns all *content* (history, prompts, injection, compaction,
  extraction, tools, memory, persistence). Kotlin talks to it via
  `hand/HandService.kt` (the agent seam over `hand/HandClient.kt`'s `/v1/run`
  SSE round loop). Every LLM call — chat loop, one-shots, memory merger — is
  a `/v1/run`: `run` streams `[HandEvent]` to the chat loop, `runCollect`
  (one-shots) consumes the same flow to a terminal `List<ChatMessage>`
  (text one-shots take the last message) — ONE loop implementation, retry
  policy, and classification system-wide.
  - Per `/v1/run`: a fresh internal `runId` (never seen by the chat loop);
    the in-flight run registers under it before the request and is evicted
    when the stream ends (success/error/cancellation; duplicate registration
    fails fast); the tool callback URL is attached on every request (the
    hand only POSTs when a tool call executes).
  - Tool advertisements travel per-round, not in the run request: the hand
    GETs `{toolListUrl}?runId=...` (`GET /api/hand/tools`,
    `hand/HandCallbackRoute.kt`) BEFORE EVERY LLM request, so MCP servers can
    add/remove tools mid-session; a failed list ends the run with
    `tool_transport` (same as a callback `fatal`). The per-round list feeds
    the LLM request's `tools` only — execution budgets never leave the
    brain. Tool execution calls back via
    `POST /api/hand/tool` (`hand/HandCallbackRoute.kt`), resolving the
    in-flight run by `runId` in `hand/HandCallbackService.kt`. The callback
    POST applies no hand-side deadline: the brain always answers (it
    enforces each tool's budget itself), a client disconnect aborts it, and
    a brain crash drops the connection — which fails the run with
    `tool_transport`.
- **ktor HTTP API** (`server/`) — `Main.kt` loads `config.jsonc`
  (`config/Config.kt`, `loadConfig`), starts DB + API. One chat run per
  request: `ChatRunService.prepareRun` validates (model REQUIRED per
  message, no server default), `runChat` runs the turn loop
  (`agent/persist/PersistChatService.kt`). Model catalog
  (`agent/ModelCatalog.kt`), chat store, and system prompt are built once and
  shared; the system prompt travels out of band (no `system` role in the
  neutral format; stored chats never contain it). Progress streams as SSE
  from `server/WebServer.kt` (incl. `tool_call`/`tool_result` echoes). The
  SSE stream is flushed with a `comment` event before the run starts: ktor
  Netty's `responseWriteTimeoutSeconds` (10s default) would otherwise kill
  a run silent during compaction/SSTM extraction (502 at the proxy).
  - **Memory** (`memory/sstm/SstmService.kt` + `PostgresSstmService.kt`,
    shared by memory CRUD routes and turn-loop injection): the loop consumes
    a versioned snapshot (`listMemories` → `MemoriesWithVersion`).
    `chats.sstm_version` is an order-sensitive SHA-256 of the `sstms` table
    (`AbstractSstmService.digestVersion`) from the last successful run; the
    injection's `<sstm-updated>` flag is `true` when the fingerprint differs
    (fresh chats store `""`, so the first run always flags). Failed runs
    never reach the store; `updateMemory` skips identical writes (no
    fingerprint churn).
  - **Locks**: per-chat `Mutex` guards concurrent runs (409) and deletes —
    `agent/chat/PostgresChatStore.store` is an upsert, so deleting mid-run
    would resurrect the row. `deleteChat` runs the SSTM extraction pipeline
    over the full history BEFORE deleting, holding the lock (entry kept in
    the map) for the whole operation; a failed extraction fails the delete
    (row survives; retry re-extracts, merger deduplicates). Lock entries are
    created atomically via `ConcurrentHashMap.compute` (`tryLock`) and
    evicted on completion/delete, so dead ids don't accumulate.
  - **ChatStore** (`agent/chat/ChatStore.kt`): all `chats`-table access
    (list/create/rename/delete + load/store) lives behind it —
    `ChatRunService` holds no raw DB calls. `load` → full `ChatEntry`
    (id/title/history/sstm version or null); `ChatInfo` (id+title) is the
    wire shape only. `renameChat`/`generateTitle` take no lock (upsert never
    touches the title).
  - **History mutation is by message INDEX** (chat array is the wire format;
    frontend renders stored order — no message ids):
    - `DELETE /api/chats/{id}/messages/{index}` (`truncateChat`): drops the
      user message at `index` and everything after it — WITHOUT SSTM
      extraction (a typo'd turn must not leak into memories) — resets
      `sstm_version` to `""` (kept history may no longer cover merged
      memories, so next run must re-flag), takes the per-chat lock (same
      upsert-resurrection argument), 400 on non-user/out-of-bounds index or
      an index leaving the chat ending mid-turn (consecutive user turns
      occur after compaction, whose summary user message sits before the
      preserved tail).
    - `POST /api/chats/{id}/fork/{index}` (`forkChat`): copies history up
      to and including the assistant message at `index` (`finishReason`
      must be `"stop"`) into a NEW row — no lock (pure read+insert; a
      racing run only makes the fork miss the in-flight turn); the fork's
      `sstm_version` starts `""` so its first run flags `sstm-updated`.
    - Both validate via `ChatCodec.validateChat`. The frontend reveals the
      actions on message hover (trash on user, fork on assistant stop),
      hides them while streaming (optimistic/uncommitted messages would
      shift indices), and confirms truncation in a dialog.
- **MCP tool servers** (`mcp/`, config under `mcp.*`; official
  `io.modelcontextprotocol:kotlin-sdk-client` 0.15.0, streamable-HTTP +
  stdio). `McpToolProvider` implements the neutral tool seam
  (`agent/tool/ToolProvider.kt`), namespacing tools as `{namespace}__{tool}`.
  `toolExecutionTimeoutSeconds` is REQUIRED per server (0 = none) and
  resolved by the callback route from the run's provider
  (`ToolProvider.executionTimeoutSeconds`): it enforces the budget with
  `withTimeout` (overrun → `isError` result, run survives). The hand
  applies no deadline of its own — the callback POST waits until the brain
  answers, the client disconnects, or the brain crashes (connection drop →
  `tool_transport`). Transport failures retry once; exhaustion throws
  `McpTransportException` → `fatal` → `tool_transport`. Result attachments
  are capability-checked against the run's model.
- **Compaction & SSTM extraction** (`agent/oneshot/compaction/`,
  `agent/oneshot/sstm/`, wired in `agent/persist/PersistChatService.kt`,
  config under `memory.*`):
  - Proactive trigger: before the round, when `currentPromptTokens(chat)`
    (last assistant message's provider-reported `meta.inputTokens` — usage
    REQUIRED on every hand response, the hand fails a round when the
    provider reports none) exceeds the run model's
    `compactionTriggerFraction × model.contextLength` (0.75–0.8 for current
    catalog entries in `agent/model/LLM.kt`; `0` disables). The not-yet-
    appended input isn't counted; the headroom absorbs it. Reactive
    fallback: EVERY hand `context_exhausted` round compacts and retries —
    no attempt cap; a compaction that fails or returns a non-clean summary
    throws and fails the run.
  - `ChatCompactionService.compactChat(fullChat, excludeLastNRound)`:
    splits at a user-turn boundary (never splitting tool_call/tool_result
    pairs; the current run's trailing tool chain stays preserved), feeds the
    WHOLE chat (drop region + marker user message "above are the messages
    to summarize, below are messages for context" + preserved tail + final
    instruction) to a `runCollect` one-shot (no tools, dedicated compaction
    prompt, ~500-word target), and replaces the drop region with one
    `CONTEXT COMPACTION: `-marked user message. A prior summary is merged
    via the prompt ("The first message might be a summarized message starts
    with marker ..."). With fewer rounds than
    `excludeLastNRound`, the keep count shrinks — down to zero (compacts
    everything), so an over-threshold chat is always compacted. Compacts or
    throws, history untouched: no user messages →
    `IllegalArgumentException`; failed/truncated/blank summary →
    `IllegalStateException`; a compactor that can't see the content
    (e.g. images + text-only model) → `ModelCapabilityException`
    (via `LLM.checkPromptContentCapabilities`) — a `memory.compactModel`
    config error.
  - `SstmExtractionService.processDiscardedMessages(droppedMessages)` runs
    on raw dropped messages BEFORE they're discarded: the **extractor**
    one-shot (no tools; raw history + attachments, capability-checked —
    a `memory.extractModel` config error) returns a fact list or the
    `Nothing worth remember.` sentinel (only skip path; blank extraction is
    a hand `empty_response` error). A failed extraction (e.g. truncated
    `length`) or one producing tool calls/no text throws and fails the run.
    The **merger** is a `/v1/run` tool loop (default ≤150 rounds; the hand
    executes `add/update/delete/list` memory tools back through the
    callback route against the `sstms` table). It runs without a lock — a
    concurrent run's injection read may observe a half-merged SSTM, healed
    by the `sstm-updated` comparison next round. Transient `upstream`
    failures retry with hand backoff; ANY terminal failure (classified hand
    error, exhausted retries, `round_limit` cap, `empty_response`) throws
    and fails the run (compaction-triggered run: nothing stored, retry
    re-runs the pipeline; deletion: row survives, retry re-extracts) — so
    unmerged memories are never lost; already-applied merges stick. The
    `sstm-updated` flag needs no plumbing: the digest changes on merge
    write and the loop compares against `chats.sstm_version` (a mid-run
    reactive compaction regenerates the latest user message's injection
    with the fresh flag + memories — and, when the keep count collapses to
    zero and replaces the whole chat, re-appends the injection with the
    run's user parts so the retried round still carries the user input).
  - Model resolution: `memory.compactModel/extractModel/mergeModel` and
    `title.model` (`agent/oneshot/TitleGenerator.kt`, used by
    `POST /api/chats/{id}/title` — no per-chat lock, like rename; titles
    from the last stored history; empty chat short-circuits; a title model
    that can't see the history → 400) are REQUIRED config (missing id fails
    at config load), resolved once at `ChatRunService` construction
    (unknown ids and a merge model without tool-call support fail fast);
    the one-shot services are constructed once and shared. A chat run's own
    model is never used for the pipeline. `title.lastNRound` (default `0`)
    caps history fed to the title model; the title generator reads the chat
    row exactly once, never the injection (stripped before every store).
    Compactions emit no dedicated SSE event — the frontend resyncs the chat
    after the run (done/error).
- **frontend/** — Svelte 5 + Vite + TS (no Gradle build step), styled after
  llama.cpp's webui: Tailwind v4 (CSS-first, tokens in `src/app.css`,
  dark-only oklch "neutral" palette), bits-ui primitives, lucide icons,
  highlight.js. Proxies `/api` to ktor; ktor serves the API only.
  - Layout: collapsible glass sidebar (chat list + search filter +
    rename/delete dialogs, generate-title, + Memories nav), centered
    `max-w-3xl` message column, floating rounded composer with circular
    send button (disabled while streaming).
  - State in `src/lib/chat-store.svelte.ts` (module-scope singleton —
    `$effect` runes NOT usable there; model-picker persistence in
    `App.svelte`). An in-flight delete locks the chat read-only via the
    store's `deletingIds` set (backend SSTM extraction can take minutes):
    the dialog closes on click (fire-and-forget), sidebar actions and send
    stay disabled until the backend confirms ("deleting chat" banner).
    Transient action errors → global toasts (`lib/toast-store.svelte.ts`,
    rendered in `App.svelte`); contextual errors stay tied to their view
    (run-error banner `streamError`, memories view inline error). SSE
    semantics preserved verbatim: tool-round commits, retry wipes, DB
    resync on done/error/abnormal close, optimistic user message; a send
    that never stores restores the composer draft. No client-side stop —
    the server only notices a disconnect on its next event write. Chat
    list, model catalog, and memories list re-fetch every 30s and on
    window focus (titles created/renamed in another session only appear via
    refetch; a failed initial catalog load retries instead of leaving a
    blank picker; SSTM merges mutate memories server-side); each replaces
    its list only when the payload changed.
  - User messages: plain-text pill bubbles (`whitespace-pre-wrap`);
    assistant: full-width markdown (marked + DOMPurify + highlight.js
    chrome from `lib/markdown.ts`). Reasoning/tool-call/tool-result parts
    in collapsible blocks (shimmer title while streaming). Auto-scroll pins
    while the user hasn't scrolled up (scroll-down button otherwise;
    switching chats re-pins). Dialogs replace `window.prompt`/`confirm`;
    model picker is a searchable chip dropdown; images via file
    picker/paste.

## Verification commands

```bash
./gradlew test
cd hand-pi && npm test && npm run build
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
- The hand is stateless and opinionless: it must never add/remove/rewrite
  message content, store anything, or log content/secrets. Kotlin-side
  changes to the wire contract (message format, events, callback payloads)
  must mirror into `hand-pi/src/types.ts` and its tests — one schema across
  DB, brain, and hand (the golden fixture `hand-pi/test/fixtures/chat-golden.json`
  and `agent/chat/ChatCodec.kt` guard it). The hand trusts Kotlin to send valid
  messages (Kotlin validates on encode): hand-side validation covers the
  request envelope only. Format conventions: no `system` role (the system
  prompt travels as the requests' `systemPrompt` field and is never stored),
  `tool_call.args` is a parsed JSON object, `reasoning.content` is a flat
  string, and a `tool_result` message carries exactly one part.
- Prefer fail-fast design

## Operational rule

+ DO NOT touch git after making changes. User should review the change and manually stage the changes.
