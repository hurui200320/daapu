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
    `server/endpoint/HandRoute.kt`) BEFORE EVERY LLM request, so MCP servers can
    add/remove tools mid-session; a failed list ends the run with
    `tool_transport` (same as a callback `fatal`). The per-round list feeds
    the LLM request's `tools` only — execution budgets never leave the
    brain. Tool execution calls back via
    `POST /api/hand/tool` (`server/endpoint/HandRoute.kt`), resolving the
    in-flight run by `runId` in `hand/HandCallbackService.kt`. A round's
    tool calls execute IN PARALLEL (all callback POSTs fire at once; results
    are reassembled into history and the SSE stream in the model's call
    order, so the wire format keeps the call→result pairing). The callback
    POST applies no hand-side deadline: the brain always answers (it
    enforces each tool's budget itself), a client disconnect aborts it, and
    a brain crash drops the connection — which fails the run with
    `tool_transport`.
  - **Embeddings** (`/v1/embed`, `hand-pi/src/embed.ts`): the plain-JSON
    sibling of `/v1/run` — one OpenAI-compatible `{baseUrl}/embeddings`
    call, fully described per request (`model.baseUrl/apiKey/modelId`,
    `dimensions`, `input`, `maxRetries`, `timeoutMs`; the hand holds no
    defaults). It maps its OWN HTTP statuses (the run endpoint's mapper
    defaults to 200 because run errors ride the SSE stream):
    `invalid_request` → 400, `auth` → 401, `upstream` → 502 (5xx/429/
    network/timeout/404/405 are `upstream` — the 404/405 case is an
    endpoint-level baseUrl misconfiguration, so it is retried with the
    shared backoff rather than misrouted to the `invalid_request`
    "split your input" channel — with `maxRetries` 0 = unlimited,
    and the response MUST carry one vector per input, realigned by the
    provider's `index` field — a gateway collapsing the batch or
    returning duplicate/gapped/missing indexes fails as `upstream`, never
    a silent short circuit). Kotlin
    side: `agent/model/EmbeddingModel.kt` (catalog entry
    `bifrost/zenmux sub/google/gemini-embedding-2`, 1536 dims — the gateway
    honors a `dimensions` request field, so the hand requests the exact
    output size; `agent/ModelCatalog.findEmbeddingModel`),
    `hand/HandClient.embed` (wire transport; parses the error envelope into
    `EmbeddingException`), `hand/HandService.embed` (seam: per-call knobs,
    wraps transport failures as `EmbeddingException("upstream")`,
    fail-fast on hand-reported dimensions ≠ catalog entry).
- **ktor HTTP API** (`server/`) — `Main.kt` loads `config.jsonc`
  (`config/Config.kt`, `loadConfig`), starts DB + API. One chat run per
  request: `ChatRunService.prepareRun` validates (model REQUIRED per
  message, no server default), `runChat` runs the turn loop
  (`agent/persist/PersistChatService.kt`). Model catalog
  (`agent/ModelCatalog.kt`) and chat store are built once and
  shared; the system prompt is rendered per run and travels out of band
  (no `system` role in the neutral format; stored chats never contain it).
  Progress streams as SSE
  from `server/WebServer.kt` (incl. `tool_call`/`tool_result` echoes). The
  SSE stream is flushed with a `comment` event before the run starts: ktor
  Netty's `responseWriteTimeoutSeconds` (10s default) would otherwise kill
  a run silent during compaction/SSTM extraction (502 at the proxy).
  - **DI** (`di/DaapuModule.kt`, Koin 4.2 + the Koin compiler plugin
    `io.insert-koin.compiler.plugin`, the project's recommended setup):
    the whole object graph is one `module { }` built by
    `daapuModule(config)`. Every definition is a `single`; the Koin
    compiler plugin DSL (`org.koin.plugin.module.dsl.single`,
    `single<ChatRunService>()` auto-wires its constructor — the classic
    `org.koin.core.module.dsl` builder DSL is gone in Koin 4, only the
    plugin DSL remains, lambda form included) gives compile-time graph
    checks.
    `ChatRunService` is pure constructor injection and holds only what its
    own methods use (chat store, catalog, title generator, tool set,
    extraction + persist services — no config, no body-built pipeline, and
    no pass-through stores: the FIXME'd `injectedEltmService` seam is gone,
    `single<EltmService>` provides it like any other service). The one-shot
    models are resolved inline in their consumers' definitions via
    `requiredLlm(...)`, which replicates the old fail-fast `require(...)`
    checks with the same messages (the recall model is NOT resolved at
    boot: the recall sub-session is unwired until Phase 4, its id is
    validated at its future definition site). Resource cleanup is Koin's:
    `onClose` on the `HandService`/`CombinedToolProvider` definitions (the
    hand HTTP client, the MCP clients) fires when the JVM shutdown hook
    calls `koinApp.close()`. `startWebServer` resolves the root
    (`koin.get<ChatRunService>()`) BEFORE the server starts, so every
    fail-fast validation and the eager MCP connect run at startup, never
    mid-run; the routes resolve `SstmService`/`EltmService`/
    `HandCallbackService` from the same container (`Application.module(koin)`)
    — the service is never a pass-through. Tests
    assemble the same module with `testutil/TestDi.kt`: `testKoinApp(...)`
    (returns the `KoinApplication`; `chatRunService(...)` = `.koin.get()`)
    with overrides for `hand = ...`, `chatStore = ...`, `sstmService = ...`,
    `eltmService = ...`, `mcpToolProvider = ...` (Koin 4 allows
    overrides by default — the override module just re-declares the seam
    types after the production one); `assertFailsFast` unwraps Koin's
    `InstanceCreationException` wrappers so the fail-fast config tests can
    pin the original error type/message.
  - **Memory** (`memory/sstm/SstmService.kt` + `PostgresSstmService.kt`,
    shared by memory CRUD routes and turn-loop injection): the loop consumes
    a versioned snapshot (`listMemories` → `MemoriesWithVersion`).
    `chats.sstm_version` is an order-sensitive SHA-256 of the `sstms` table
    (`AbstractSstmService.digestVersion`) from the last successful run; the
    injection's `<sstm-updated>` flag is `true` when the fingerprint differs
    (fresh chats store `""`, so the first run always flags). Failed runs
    never reach the store; `updateMemory` skips identical writes (no
    fingerprint churn).
  - **ELTM** (`memory/eltm/EltmService.kt` + `PostgresEltmService.kt`, the
    diary model): entities `(id, name, category)` + **attributes**
    (`(entity_id, key, value)` — current-state key-value facts, e.g. a
    kindle's `model`, a person's `realname`/`nickname`: one row per
    (entity, key), setting again overwrites, deleting removes, keys
    canonicalized like verbs and values required single-line) and
    relationships
    `(src, verb, dst, valid)` hold NO diary content — all descriptive
    content
    lives in **notes**, add-only dated diary entries `(subject, event_date,
    note)` strictly single-subject (one entity XOR one relationship, a
    migration CHECK). `valid=false` is a soft delete;
    re-establishing an ended triple is a diary event
    (`add_relationship_note` with `valid=true`). There is NO stored mention counter —
    prominence is computed on read: `EntityView(noteCount,
    relationshipCount, …)`, `RelationshipView(noteCount, …)`,
    `EntityWithScore(noteCount, relationshipCount, score)` (a true
    historical count is unreconstructable: no event ledger; entities and
    relationships carry NO timestamps — content lives in the notes).
    Write path =
    the SSTM purge only: `SstmExtractionService.purgeSstmToEltm` (after
    extraction/merge or its skip) moves the oldest `memory.sstm.purgeBatchSize`
    SSTM rows per batch through the ELTM writer agent
    (`agent/oneshot/eltm/EltmWriterService.kt` + 12-tool
    `EltmToolProvider.kt` — RW mode; the same provider's `readOnly`
    mode = the 5 read tools for the Phase 4 recall sub-session — `runCollect`
    tool loop, model =
    `memory.eltm.writerModel`, cap `maxWriterRounds`) and deletes each batch
    ONLY after its writer run succeeds — a writer failure fails the run,
    purged batches stay purged (content safe in the ELTM), surviving rows
    retry idempotently. Writer semantics: create exact-matches are pure
    reads (create-or-fetch — nothing is ever updated: renaming an entity is
    create + merge), unique violations (concurrent runs) re-select
    the winner, `merge_entities` folds colliding triples (the
    duplicate's notes re-pointed BEFORE its delete, so the
    cascade never eats diary notes) and invalidates re-pointed self-loops;
    relationship endings are diary events — `add_relationship_note`'s `valid`
    flag
    (`false` = the edge ends, `true` = it holds again, e.g. rejoined the
    company; omitted = unchanged) applies the structural change with the
    explanatory note in ONE transaction (one counter bump; idempotent —
    setting the current state is a no-op). The ELTM tools mirror the
    service one-to-one (no entity/relationship mixing in one tool:
    `create_entity`/`create_relationship`, `get_entity_notes`/
    `get_relationship_notes`, `add_entity_note`/
    `add_relationship_note`, `set_entity_attribute`/
    `delete_entity_attribute`, plus the shared reads `search_entities`,
    `get_relationships`, `search_notes`); read tools fail fast on
    nonexistent subjects and malformed date filters. Attribute writes
    re-embed the entity (the embedding text is `name + category` plus the
    attributes as `key: value` lines, alphabetically by key — so facts are
    semantically searchable), and `merge_entities` folds the loser's
    attributes into the winner (the winner's value wins a colliding key).
    There is exactly ONE
    `eltm_relationships` row per triple (full unique index): `valid` is a
    state of the relationship, never a second row — it only changes
    through diary events (`add_relationship_note`'s `valid` flag),
    and merge folds a colliding triple
    into the survivor (validity OR, notes re-pointed);
    an embedding `invalid_request` answers an `isError` "split it into
    several smaller notes" tool result (never truncated). **Read path**:
    the same provider's `readOnly` mode (the 5 read tools) is currently
    namespaced as `eltm` and combined with the MCP provider into the chat
    loop's tool set (`ChatRunService.chatToolProvider` — the main agent
    queries the ELTM directly, a debugging/interim surface until the
    Phase 4 `recall` sub-session tool, planned under the `gsg` namespace,
    offloads the browsing; the system prompt's developer note documents
    this). `memory.eltm.recallModel` is NOT
    resolved yet: the recall sub-session is unwired, so its id is only
    validated at the Phase 4 definition site (unlike the writer/embedding
    models, which fail fast at boot). `chats.eltm_version` mirrors `sstm_version` ("" = first run
    flags); the version is the global write counter
    (`EltmService.version()`) — every visible-state write (entity/
    relationship insert, revive, invalidation, merge, note append,
    attribute set when the value changes / delete) bumps
    the counter
    (`memory_meta_number.eltm_version`, an atomic
    `value = value + 1` UPDATE inside the write's OWN transaction — never
    on a no-op upsert touch, so a missed bump would silently break the
    flag), NOT a content hash. The persist loop reads `EltmService.version()`
    AFTER the pre-round compaction (purge writes during extraction must
    count), compares it against the stored `chats.eltm_version` at both
    injection sites (`eltmUpdated`), and stamps the version it saw on the
    successful store — like `sstm_version`, a failed run never moves it.
    **Vector columns are FIXED at `vector(2000)`**
    (pgvector's HNSW limit, `MAX_VECTOR_DIMENSIONS` in `config/Config.kt`,
    `db/VectorColumnType.kt`): the embedding model's output dimensions
    (catalog entry, `memory.eltm.embeddingModel`, must be ≤ 2000 — fail
    fast at catalog construction) only decide the nonzero prefix — every
    vector and query is zero-padded to 2000 on write (`padVector`), and
    cosine similarity is invariant under zero-padding, so switching
    embedding models never needs a schema change or DB reset. Similarity
    queries use Exposed's built-in pgvector support (`VectorDistance` with
    `VectorDistanceMetric.COSINE`, rendered as the `<=>` operator; the query
    vector travels as a `QueryParameter` typed by `VectorColumnType`).
  - **Context injection & time anchors** (`agent/persist/ContextInjection.kt`):
    user messages carry a stored `createdAt` (UTC `Instant`, user-only —
    assistant timing is implied by the surrounding user messages; required on
    stored user messages by `ChatCodec.validateChat` + `PostgresChatStore.store`,
    fail fast otherwise). At prompt-build time `injectContext(chat, spec)`
    prepends a `<meta><sent-at>…</sent-at></meta>` anchor to every historical
    user message (rendered from `createdAt` in the server's CURRENT zone, so a
    server zone change re-renders every anchor consistently — the model never
    sees mixed offsets) and, when a spec is given (the chat loop only), the
    full `<injection>` on the latest user message (stamping its `createdAt`
    when missing). One-shot services pass a null spec: anchors only, no
    injection. `removeInjection` strips both before every store — stored chats
    never carry harness XML. Both are idempotent and guarded: a `<meta>` is
    only recognized when it byte-matches the deterministic render of the
    message's own `createdAt` (forged lookalikes stay as user content), the
    `<injection>` structurally via the XSD. Harness parts never outlive the
    request — anchors are regenerated per request and stripped before every
    store, so a stored chat can never carry a stale anchor and a zone change
    can never strand one in storage. Compaction and SSTM extraction — the
    one-shots that may receive the loop's injected in-loop chat — sanitize
    their input first (`removeInjection`) then re-anchor, so the loop's
    injected in-loop chat never double-injects; `TitleGenerator` needs
    neither: it reads the stored row, which is always clean.
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
    (id/title/history/sstm + eltm versions or null); `ChatInfo` (id+title) is the
    wire shape only. `renameChat`/`generateTitle` take no lock (upsert never
    touches the title).
  - **History mutation is by message INDEX** (chat array is the wire format;
    frontend renders stored order — no message ids):
    - `DELETE /api/chats/{id}/messages/{index}` (`truncateChat`): drops the
      user message at `index` and everything after it — WITHOUT SSTM
      extraction (a typo'd turn must not leak into memories) — resets
      `sstm_version` to `""` (kept history may no longer cover merged
      memories, so next run must re-flag) and `eltm_version` to `""` the
      same way (kept history may no longer cover the ELTM), takes the
      per-chat lock (same
      upsert-resurrection argument), 400 on non-user/out-of-bounds index or
      an index leaving the chat ending mid-turn (consecutive user turns
      occur after compaction, whose summary user message sits before the
      preserved tail).
    - `POST /api/chats/{id}/fork/{index}` (`forkChat`): copies history up
      to and including the assistant message at `index` (`finishReason`
      must be `"stop"`) into a NEW row — no lock (pure read+insert; a
      racing run only makes the fork miss the in-flight turn); the fork's
      `sstm_version` and `eltm_version` start `""` so its first run flags
      `sstm-updated`/`eltm-updated`.
    - Both validate via `ChatCodec.validateChat`. The frontend reveals the
      actions on message hover (trash on user, fork on assistant stop),
      hides them while streaming (optimistic/uncommitted messages would
      shift indices), and confirms truncation in a dialog.
- **MCP tool servers** (`mcp/`, config under `mcp.*`; official
  `io.modelcontextprotocol:kotlin-sdk-client` 0.15.0, streamable-HTTP +
  stdio). `McpToolProvider` implements the neutral tool seam
  (`agent/tool/ToolProvider.kt`), namespacing tools as `{namespace}__{tool}`.
  Namespaces are a `ToolProvider` contract (`namespaces()`): a namespaced
  provider advertises every tool as `{namespace}__{toolName}` and only
  executes those prefixed names; an empty set = the one-shot shape (bare
  tool names, the `EltmToolProvider`/`MergeMemoryToolProvider` defaults —
  one-shot services never namespace). `CombinedToolProvider`
  (`agent/tool/CombinedToolProvider.kt`) merges several children (MCP +
  namespaced local providers) into one run's tool set: every child MUST
  serve at least one non-blank namespace, validated (`SAFE_ID_REGEX`, no
  `__`) and unique across children, fail fast at construction; routing
  splits the advertised name at the first `__` (unknown prefix/bare name →
  `isError` result), `executionTimeoutSeconds` delegates to the owning
  child, and `close()` closes `AutoCloseable` children.
  `toolExecutionTimeoutSeconds` is REQUIRED per server (0 = none) and
  resolved by the callback route from the run's provider
  (`ToolProvider.executionTimeoutSeconds`): it enforces the budget with
  `withTimeout` (overrun → `isError` result, run survives). The hand
  applies no deadline of its own — the callback POST waits until the brain
  answers, the client disconnects, or the brain crashes (connection drop →
  `tool_transport`). A transport failure mid-execution (no retry or
  reconnect) drops the cached client and answers an error tool-result; the
  next round's tool-list refresh (`GET /api/hand/tools` →
  `specifications`) is the SOLE reconnection point — it reconnects, or
  throws `McpTransportException` → `fatal` → `tool_transport` when the
  server stays down. Result attachments are capability-checked against the
  run's model.
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
    treats its input as potentially injected (a reactive compaction receives
    the chat loop's injected in-loop chat): sanitize (`removeInjection`) first,
    then anchor the stamped user messages (`injectContext` with a null spec —
    anchors only, the summarizer never sees a full injection), splits at a
    user-turn boundary (never splitting tool_call/tool_result
    pairs; the current run's trailing tool chain stays preserved), feeds the
    WHOLE chat (drop region + marker user message "above are the messages
    to summarize, below are messages for context" + preserved tail + final
    instruction) to a `runCollect` one-shot (no tools, dedicated compaction
    prompt, ~500-word target), and replaces the drop region with one
    `CONTEXT COMPACTION: `-marked user message stamped with its own
    `createdAt` (it is stored). A prior summary is merged
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
    on raw dropped messages BEFORE they're discarded: it sanitizes them
    (`removeInjection`) and re-anchors every user message with its own
    `<meta>` send time (`injectContext`, null spec — the extractor is
    STATELESS: no current date anywhere in its input or prompt, so the
    extraction time never matters; every relative date resolves against the
    message's own anchor). The **extractor**
    one-shot (no tools; raw history + attachments, capability-checked —
    a `memory.sstm.extractModel` config error) returns a fact list or the
    `Nothing worth remember.` sentinel (only skip path; blank extraction is
    a hand `empty_response` error). A failed extraction (e.g. truncated
    `length`) or one producing tool calls/no text throws and fails the run.
    The **merger** is a `/v1/run` tool loop (≤ `memory.sstm.maxMergeRounds`
    rounds, default 150; the hand executes `add/update/list` memory tools
    back through the callback route against the `sstms` table). It runs
    without a lock — a
    concurrent run's injection read may observe a half-merged SSTM, healed
    by the `sstm-updated` comparison next round. Transient `upstream`
    failures retry with hand backoff; ANY terminal failure (classified hand
    error, exhausted retries, `round_limit` cap, `empty_response`) throws
    and fails the run (compaction-triggered run: nothing stored, retry
    re-runs the pipeline; deletion: row survives, retry re-extracts) — so
    unmerged memories are never lost; already-applied merges stick. The
    `sstm-updated` flag needs no plumbing: the digest changes on merge
    write and the loop compares against `chats.sstm_version` (a mid-run
    reactive compaction re-injects the latest user message's `<injection>`
    with the fresh flag + memories via a fresh `injectContext` — and, when
    the keep count collapses to zero and replaces the whole chat, the loop
    re-appends the run's user parts first so the retried round still
    carries the user input).
  - `SstmExtractionService.purgeSstmToEltm` runs at the END of
    `processDiscardedMessages` — after the extraction/merge OR its skip (an
    over-capacity SSTM whose dropped content is unmemorable still purges):
    while the SSTM's total memory content char length exceeds
    `memory.sstm.maxCapacity` (a model-agnostic proxy for the injected SSTM
    size), the oldest `purgeBatchSize` memories (by `(last_update, id)`)
    are handed to the
    ELTM writer agent (see the ELTM bullet) and deleted from the SSTM only
    after the writer run succeeds; a writer failure throws (`ELTM write
    failed`) and fails the run, already-purged batches stay purged.
  - Model resolution: `memory.compactModel` + `memory.sstm.extractModel/
    mergeModel` and
    `title.model` (`agent/oneshot/TitleGenerator.kt`, used by
    `POST /api/chats/{id}/title` — no per-chat lock, like rename; titles
    from the last stored history; empty chat short-circuits; a title model
    that can't see the history → 400) are REQUIRED config (missing id fails
    at config load), resolved once at startup by the DI container
    (`di/DaapuModule.kt`; unknown ids and a merge model without tool-call
    support fail fast);
    the one-shot services are constructed once and shared. A chat run's own
    model is never used for the pipeline. The ELTM models
    (`memory.eltm.embeddingModel/writerModel/recallModel`) are REQUIRED the
    same way — `memory.eltm` is mandatory config for every deployment (the
    SSTM purge and the recall tool are unconditional system-prompt
    promises); writer/recall must support tool calls; the embedding entry's
    `dimensions` must not exceed `MAX_VECTOR_DIMENSIONS` (checked at
    catalog construction). The embedding/writer ids fail fast at boot
    (resolved by the container's `EltmService`/`EltmWriterService`
    definitions); the recall id is only validated at its Phase 4
    definition site (the sub-session is unwired). The
    writer/recall/embedding one-shot knobs come
    from `memory.eltm.*` + `hand.*` (the embed timeout is the hand's
    `streamIdleTimeoutMs`). `title.lastNRound` (default `0`)
    caps history fed to the title model; the title generator reads the chat
    row exactly once, never the injection (harness parts are removed before
    every store).
    Compactions emit no dedicated SSE event — the frontend resyncs the chat
    after the run (done/error).
- **frontend/** — Svelte 5 + Vite + TS (no Gradle build step), styled after
  llama.cpp's webui: Tailwind v4 (CSS-first, tokens in `src/app.css`,
  dark-only oklch "neutral" palette), bits-ui primitives, lucide icons,
  highlight.js. Proxies `/api` to ktor; ktor serves the API only.
  - Layout: collapsible glass sidebar (chat list + search filter +
    rename/delete dialogs, generate-title, + SSTM/ELTM nav), centered
    `max-w-3xl` message column, floating rounded composer with circular
    send button (disabled while streaming).
  - Routing: hash-based (`src/lib/router.svelte.ts`, zero deps — the hash
    never reaches the server, so any static host works without SPA fallback
    config; the same reason llama.cpp's webui uses `router: hash`). Routes:
    `#/chat` (home),     `#/chat/<id>`, `#/sstm`, `#/eltm`. The URL
    owns the active view and the open chat: an `App.svelte` `$effect`
    translates the route into `chatStore.pickChat`/`closeChat`, and store
    actions that change the open chat (create/fork/delete) navigate the hash
    (`navigate` updates the route state synchronously — the `hashchange`
    event alone would land after the effect, transiently re-picking the stale
    route; delete uses `replaceRoute` on the chat view, and the route effect
    redirects a later back/forward landing on a session-deleted chat's route
    to home — the load would 404 — so the deleted chat never survives as a
    back target). Chat-route changes are ignored while a run streams
    (back/forward, URL edits), mirroring the sidebar's streaming lock; the
    pending chat route applies when the run ends — and a run that failed
    while the route had left the chat surfaces its error as a toast, since
    the banner would be wiped with the view. Views stay mounted, CSS-hidden
    by route, so a live stream and the composer draft survive tab switches.
  - State in `src/lib/chat-store.svelte.ts` (module-scope singleton —
    `$effect` runes NOT usable there; model-picker persistence in
    `App.svelte`). An in-flight delete locks the chat read-only via the
    store's `deletingIds` set (backend SSTM extraction can take minutes):
    the dialog closes on click (fire-and-forget), sidebar actions and send
    stay disabled until the backend confirms ("deleting chat" banner).
    Transient action errors → global toasts (`lib/toast-store.svelte.ts`,
    rendered in `App.svelte`); contextual errors stay tied to their view
    (run-error banner `streamError`, SSTM view inline error). SSE
    semantics preserved verbatim: tool-round commits, retry wipes, DB
    resync on done/error/abnormal close, optimistic user message; a send
    that never stores restores the composer draft. No client-side stop —
    the server only notices a disconnect on its next event write. Chat
    list, model catalog, memories list, and the ELTM view's entity/
    relationship lists re-fetch every 30s and on
    window focus (titles created/renamed in another session only appear via
    refetch; a failed initial catalog load retries instead of leaving a
    blank picker; SSTM merges mutate memories server-side, the SSTM purge
    writes the ELTM server-side); each replaces
    its list only when the payload changed.
  - The ELTM view (`EltmView.svelte`, route `#/eltm`) is browse-only (writes
    are LLM-driven): two sub-tabs — Entities (cards with attribute chips,
    counts + latest
    note, expandable to lazily fetch the entity's relationships and diary
    via the `/api/eltm` drill-down routes) and Relationships (cards with
    endpoint names + validity badge, expandable to fetch their notes). Both
    lists paginate via a load-more button (100 rows per page, oldest first);
    the background resync refetches the loaded window, so appended pages
    survive and a server-side shrink shrinks the list too.
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
