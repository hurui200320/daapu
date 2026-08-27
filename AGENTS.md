# AGENTS.md

Guidance for AI agents (and humans) working in this project.

## Project

PoC of a chatbot with a memory system: Kotlin/JVM (Gradle) brain + Svelte
frontend + Node/TS "hand-pi" service.

### Behavioral Guidelines: Micro-Sessions & Memory Isolation

Agents (and users) should adhere to the **Micro-Session** philosophy:
- **One Topic Per Session:** Do not maintain a single, infinite chat session. Open a new chat for each specific task, feature implementation, or distinct topic. This prevents attention decay, hallucination snowballing, and reduces token costs.
- **Task-Oriented & Extract:** When a task is completed, truncate or delete the chat. This actively triggers the memory extraction pipeline (`MemoryExtractionService`), writing distilled facts into the ELTM (External Long-Term Memory).
- **Memory Isolation:** There is no global shared short-term memory (SSTM) injected into all active chats. Cross-polluting contexts (e.g., mixing code-style preferences with a discussion about literature) degrades reasoning. Context is pulled strictly on-demand via the ELTM and `QueryRewriteService`.

### Architecture

- **PostgreSQL + pgvector** — Exposed access, Flyway schema in
  `src/main/resources/db/migration/`.
- **hand-pi** (`hand-pi/`, `@earendil-works/pi-ai` 0.84.1) — stateless,
  opinionless LLM *execution*: streaming, dialects, tool-call accumulation,
  retries, usage. No catalog/sessions/prompts; everything arrives per request.
  Kotlin owns all *content* (history, prompts, injection, compaction,
  extraction, tools, memory, persistence) via `hand/HandService.kt`, the seam
  over `hand/HandClient.kt`'s `/v1/run` SSE round loop. Every LLM call is a
  `/v1/run`: `run` streams `[HandEvent]` to the chat loop; `runCollect`
  (one-shots) consumes the same flow to a terminal `List<ChatMessage>` (text
  one-shots take the last message); `runCollectPartial` runs the same loop but
  returns the collected messages plus the terminal `HandRunException` instead
  of throwing (a dropped transport still throws `HandUpstreamException`) — the
  Phase 4 investigate agent's recovery hook. ONE loop implementation, retry
  policy, and classification system-wide.
  - Per `/v1/run`: a fresh internal `runId` (never seen by the chat loop); the
    in-flight run registers under it before the request and is evicted when the
    stream ends (duplicate registration fails fast); the tool callback URL
    attaches on every request (the hand only POSTs when a tool executes).
  - Tool ads travel per-round, not in the run request: the hand GETs
    `{toolListUrl}?runId=...` (`GET /api/hand/tools`,
    `server/endpoint/HandRoute.kt`) BEFORE EVERY LLM request, so MCP servers can
    add/remove tools mid-session; a failed list ends the run with
    `tool_transport` (same as a callback `fatal`). The per-round list feeds the
    request's `tools` only — execution budgets never leave the brain. Execution
    calls back via `POST /api/hand/tool` (`HandRoute.kt`), resolving the run by
    `runId` in `hand/HandCallbackService.kt`. A round's tool calls execute IN
    PARALLEL; results reassemble into history and SSE in the model's call order.
    The callback POST has no hand-side deadline: the brain always answers (it
    enforces each tool's budget itself), a disconnect aborts it, a crash drops
    the connection → `tool_transport`.
  - **Embeddings** (`/v1/embed`, `hand-pi/src/embed.ts`): plain-JSON sibling of
    `/v1/run` — one OpenAI-compatible `{baseUrl}/embeddings` call, fully
    described per request (`model.baseUrl/apiKey/modelId`, `dimensions`, `input`,
    `maxRetries`, `timeoutMs`, optional `additionalProperties` — extra
    root-level fields merged into the gateway request body, e.g. deepinfra's
    `service_tier: "priority"`, sourced from the catalog entry's
    `EmbeddingModel.additionalProperties`; keys colliding with the
    hand-managed `model`/`input`/`dimensions` are rejected `invalid_request`;
    no hand defaults). Maps its OWN statuses:
    `invalid_request` → 400, `auth` → 401, `upstream` → 502 (5xx/429/network/
    timeout are transient `upstream`, retried with shared backoff; 404/405 =
    endpoint-level baseUrl misconfig, likewise retried; `maxRetries` 0 =
    unlimited); the response MUST carry one vector per
    input, realigned by the provider's `index` — a gateway that collapses/
    duplicates/gaps/misses indexes fails as `upstream`. Kotlin:
    `agent/model/EmbeddingModel.kt` (catalog entry from the config's
    `providers.<id>.embedding`, e.g. `bifrost/zenmux sub/google/gemini-embedding-2`,
    1536 dims; the gateway
    honors a `dimensions` field), `hand/HandClient.embed` (→
    `EmbeddingException`), `hand/HandService.embed` (per-call knobs; transport
    failures → `EmbeddingException("upstream")`; fail-fast on dimensions ≠
    catalog entry).
- **ktor HTTP API** (`server/`) — `Main.kt` loads `config.jsonc`
  (`config/Config.kt`), starts DB + API. One chat run per request:
  `ChatRunService.prepareRun` validates (model AND personaId REQUIRED per
  message, no server default), `runChat` runs the turn loop
  (`agent/persist/PersistChatService.kt`).
  Catalog (`agent/ModelCatalog.kt`, built from the config's
  `providers.<id>.llm/embedding` entries — at least one LLM entry required,
  duplicate composite ids fail fast) and chat store are built once and shared;
  the system prompt is rendered per run and travels out of band (no `system`
  role; stored chats never contain it). SSE from `server/WebServer.kt` (incl.
  `tool_call`/`tool_result` echoes), flushed with a `comment` event before the
  run starts — ktor Netty's 10s `responseWriteTimeoutSeconds` would otherwise
  kill a run silent during compaction/memory extraction.
  - **Personas** (`agent/persona/`): the main agent's prompt is split into a
    user-managed persona (the DEFAULT persona's text covers identity,
    personality and the policy — a custom persona may write its own; plus a
    tool-namespace whitelist) and the code-owned GSG harness introduction
    (`agent/persist/MainAgentSystemPromptService.kt` — harness mechanics,
    context injection docs). The harness introduction is gated on the
    persona's whitelist serving `gsg`: WITH it (or empty = all) the full
    introduction is rendered (`gsg__investigate` docs + ELTM context
    injection docs); WITHOUT it a reduced `# Context` section documents only
    the actually injected parts (`<meta>` anchors, `localtime`, compaction
    summaries), and the run's injection is the time-only simple shape — no
    `eltm-updated`, no `<memories>` — with the query rewrite + ELTM searches
    skipped (they only feed the hidden memories). The DEFAULT
    persona (reserved id 0)
    lives ONLY in code (prompt updates need no data sync), is never a
    `personas` row (the API rejects create/update/delete on it) and has an
    EMPTY whitelist = ALL namespaces the chat loop serves. Personas rows:
    `(id, name, system_prompt, allowed_namespaces)` — the row id is a
    BIGSERIAL DB identity carried by the wire types as the number itself
    (the reserved code-only id 0 never collides: sequences start at 1). The
    whitelist is a
    JSON array validated at save time against the chat loop's boot-time
    namespace snapshot (`PersonaService`; `[]` = all; a namespace the loop
    does not serve — including `eltm` — is a 400). The request owns the
    persona: `SendMessageRequest.personaId` is resolved in `prepareRun`
    (unknown id → 400, no silent fallback), which also wraps the loop's tool
    set in a `WhitelistedToolProvider` per request (an empty whitelist = the
    set unfiltered; a whitelist entry the loop no longer serves after a
    config change fails with a clear 400 before any stream starts); `runChat`
    renders the prompt and runs. `chats.persona_id` is ONLY a record: it
    starts at the reserved id 0, is inherited by forks, and is stamped by the
    successful run's store upsert — never consulted for prompt/tool
    resolution. Routes: `GET/POST /api/personas`, `PUT/DELETE
    /api/personas/{id}` (400 on the reserved id 0), listed in `server/endpoint/
    PersonasRoute.kt`.
  - **DI** (`di/AppModule.kt`, Koin 4.2 + the compiler plugin
    `io.insert-koin.compiler.plugin`): one `module { }` built by
    `AppModule(config)`, every definition a `single` via the plugin DSL
    (classic builder DSL gone in Koin 4) — compile-time graph checks.
    `ChatRunService` is pure constructor injection holding only what its methods
    use (no config, no body-built pipeline, no pass-through stores). One-shot
    models resolve inline via `requiredLlm(...)` (fail-fast; the investigate
    agent's model is resolved at boot like the other one-shot models — the
    sub-session is wired into the graph root through the loop's
    `gsg__investigate` tool).
    Resource cleanup is Koin's: `onClose` on `HandService`/
    `McpToolProvider` fires when the JVM shutdown hook calls
    `koinApp.close()` (the MCP provider closes its cached clients itself;
    `CombinedToolProvider` owns no resources). `startWebServer` resolves the root
    (`koin.get<ChatRunService>()`) BEFORE the server starts, so fail-fast
    validation and the eager MCP connect run at startup; routes resolve
    `EltmService`/`HandCallbackService` from the same container
    (`Application.module(koin)`). Tests: `testutil/TestDi.kt` —
    `testKoinApp(...)`/`chatRunService(...)` with overrides for `hand`/
    `chatStore`/`eltmService`/`mcpToolProvider`/`personaStore` (the persona
    store defaults to an in-memory `FakePersonaStore` — the Postgres store
    would need a live database); `assertFailsFast` unwraps
    Koin's `InstanceCreationException` wrappers.
  - **ELTM** (`memory/eltm/EltmService.kt` + `PostgresEltmService.kt`, the diary
    model): entities `(id, name, category)` + **attributes**
    (`(entity_id, key, value)` — current-state key-value facts, one row per
    (entity, key), set overwrites/delete removes) and relationships
    `(src, verb, dst, valid)` hold NO content — all descriptive content lives in
    **notes**, add-only dated diary entries `(subject, event_date, note)`
    strictly single-subject (entity XOR relationship, a migration CHECK).
    `valid=false` is a soft delete; re-establishing an ended triple is a diary
    event (`add_relationship_note` `valid=true`). NO stored mention counter —
    prominence is computed on read (`EntityView`/`RelationshipView`/
    `EntityWithScore`). Write path = the extraction pipeline only: extractor
    facts go through the ELTM writer agent (`agent/oneshot/eltm/
    EltmWriterService.kt` + 13-tool `EltmToolProvider.kt`, RW mode; the same
    provider's `readOnly` mode = the 5 read tools for the investigate
    sub-agent's own tool set; `runCollect` tool loop, model
    `memory.eltm.writerModel`, cap `maxWriterRounds`). A writer failure fails
    the run; a retry re-extracts (the writer skips already-recorded content).
    Writer semantics: create exact-matches are pure reads (create-or-fetch;
    identity changes go through `refine_entity`, renaming in place and keeping
    the id so notes/relationships/attributes stay attached; identical
    (name, category) is a no-op, a collision errors so the model merges);
    unique violations re-select the winner; `merge_entities` folds colliding
    triples (duplicate's notes re-pointed BEFORE its delete, so the cascade
    never eats diary notes) and invalidates re-pointed self-loops; relationship
    endings are diary events — `add_relationship_note`'s `valid` flag applies
    the structural change with the note in ONE transaction (idempotent). The 13
    tools mirror the service one-to-one (`create_entity`/`refine_entity`/
    `create_relationship`, `get_entity_notes`/`get_relationship_notes`,
    `add_entity_note`/`add_relationship_note`, `set_entity_attribute`/
    `delete_entity_attribute`, shared reads `search_entities`/
    `get_relationships`/`search_notes`); read tools fail fast on nonexistent
    subjects and malformed dates. Attribute writes re-embed the entity (`name +
    category` + `key: value` lines alphabetically); `merge_entities` folds the
    loser's attributes (winner wins a colliding key). Exactly ONE
    `eltm_relationships` row per triple (full unique index); `valid` is a state,
    only changed via diary events; merge folds a colliding triple into the
    survivor (validity OR, notes re-pointed); an embedding `invalid_request`
    answers an `isError` "split it into several smaller notes and retry." tool
    result. **Read
    path**: the provider's `readOnly` mode, namespaced `eltm`, is combined with
    the MCP provider into the investigate sub-agent's OWN tool set (a second
    `CombinedToolProvider`, built in the DI module — NOT the chat loop's
    set). The main chat loop reaches the ELTM only through the
    `gsg__investigate` tool (`agent/persist/GsgToolProvider.kt`, namespace
    `gsg`). `chats.eltm_version`
    ("" = first run flags) is the global write counter (`EltmService.version()`):
    every visible-state write (entity/relationship insert, revive,
    invalidation, merge, note append, attribute set/delete when the value
    changes) bumps it via an atomic `value = value + 1` UPDATE inside the
    write's OWN transaction (never on a no-op upsert touch), NOT a content hash.
    The persist loop reads `EltmService.version()` AFTER pre-round compaction,
    compares it against `chats.eltm_version` at both injection sites
    (`eltmUpdated`), and stamps it on the successful store — a failed run never
    moves it. **Vector columns are FIXED at `vector(2000)`** (pgvector's HNSW
    limit, `MAX_VECTOR_DIMENSIONS` in `config/Config.kt`,
    `db/VectorColumnType.kt`): the embedding model's output dims (catalog entry
    `memory.eltm.embeddingModel`, must be ≤ 2000, fail fast at catalog
    construction) only decide the nonzero prefix — every vector/query is
    zero-padded to 2000 (`padVector`); cosine similarity is invariant under
    zero-padding, so switching embedding models needs no schema change. Similarity
    uses Exposed's pgvector support (`VectorDistance` COSINE, rendered as `<=>`).
  - **Context injection & time anchors** (`agent/persist/ContextInjection.kt`):
    user messages carry a stored `createdAt` (UTC `Instant`, user-only; required
    on stored user messages by `ChatCodec.validateChat` +
    `PostgresChatStore.store`). At prompt-build time `injectContext(chat, spec)`
    prepends a `<meta><sent-at>…</sent-at></meta>` anchor to every historical
    user message (rendered in the server's CURRENT zone, so a zone change
    re-renders every anchor consistently) and, when a spec is given (the chat
    loop only), the full `<injection>` on the latest user message (stamping
    `createdAt` when missing). One-shots pass a null spec: anchors only.
    `removeInjection` strips both before every store — stored chats never carry
    harness XML. Both are idempotent and guarded: a `<meta>` is only recognized
    when it byte-matches the deterministic render of the message's own
    `createdAt`; the `<injection>` structurally via two XSDs pinning the two
    shapes the generator emits (the full ELTM shape in
    `injectionSchema.xsd`, the time-only simple shape in
    `injectionSimpleSchema.xsd` — a hybrid, e.g. `eltm-updated` without
    `<memories>`, validates against neither and survives as user content). The `<injection>`'s
    `<memories>` carries the ELTM context injection (`<related-entities>`/
    `<related-notes>`, always present when the ELTM is injected, possibly
    empty; searched by the rewritten
    query): `<entity id name category>` with `<attribute key>` facts, and
    `<note id date subject-type>` whose subject is identified by names (entity:
    `name`+`category`; relationship: `src-name`+`verb`+`dst-name`, resolved
    before rendering via `RelatedNoteView`). The XSD declares the note's subject
    attributes as the UNION of both shapes (XSD 1.0 can't express the XOR); the
    generator always emits exactly one set — everything else strict, so forged
    injections are rejected. The ELTM spec fields (`eltmUpdated`/`relatedEntities`/
    `relatedNotes`) are all-null together (a persona without `gsg` access gets the
    time-only simple injection — `localtime` only, no `eltm-updated`, no
    `<memories>`) or all non-null together (the full ELTM injection, empty lists
    when nothing related was found) — a mixed spec is rejected at construction.
    Harness parts never outlive the request. Compaction,
    memory extraction, and query rewrite — the one-shots that may receive the
    loop's injected in-loop chat — sanitize (`removeInjection`) then re-anchor,
    never double-injecting; `TitleGenerator` needs neither (it reads the clean
    stored row).
  - **Locks**: per-chat `Mutex` guards concurrent runs (409) and deletes —
    `PostgresChatStore.store` is an upsert, so deleting mid-run would resurrect
    the row. `deleteChat` runs the extraction pipeline over the full history
    BEFORE deleting, holding the lock for the whole operation; a failed
    extraction fails the delete (row survives; retry re-extracts). Lock entries
    are created atomically via `ConcurrentHashMap.compute` (`tryLock`) and
    evicted on completion/delete.
  - **ChatStore** (`agent/chat/ChatStore.kt`): all `chats`-table access
    (list/create/rename/delete + load/store) lives behind it — `ChatRunService`
    holds no raw DB calls. `load` → full `ChatEntry` (id/title/history + eltm
    version + persona record or null); `ChatInfo` (id+title+persona record) is
    the wire shape only.
    `renameChat`/`generateTitle` take no lock.
  - **History mutation is by message INDEX** (chat array is the wire format;
    frontend renders stored order — no message ids):
    - `DELETE /api/chats/{id}/messages/{index}` (`truncateChat`): drops the user
      message at `index` and everything after it — WITHOUT memory extraction (a
      typo'd turn must not leak into memories) — resets `eltm_version` to `""`
      (the next run must re-flag), leaves the persona record untouched, takes
      the per-chat lock, 400 on
      non-user/out-of-bounds index or an index leaving the chat ending mid-turn.
    - `POST /api/chats/{id}/fork/{index}` (`forkChat`): copies history up to and
      including the assistant message at `index` (`finishReason` must be
      `"stop"`) into a NEW row — no lock (pure read+insert); the fork's
      `eltm_version` starts `""` so its first run flags `eltm-updated`.
    - Both validate via `ChatCodec.validateChat`. The frontend reveals the
      actions on message hover (trash on user, fork on assistant stop), hides
      them while streaming, and confirms truncation in a dialog.
- **MCP tool servers** (`mcp/`, config under `mcp.*`; official
  `io.modelcontextprotocol:kotlin-sdk-client` 0.15.0, streamable-HTTP + stdio).
  `McpToolProvider` implements the neutral tool seam (`agent/tool/ToolProvider.kt`),
  namespacing tools as `{namespace}__{tool}`. Namespaces are a `ToolProvider`
  contract (`namespaces()`): a namespaced provider advertises every tool as
  `{namespace}__{toolName}` and only executes prefixed names; an empty set = the
  one-shot shape (bare names, `EltmToolProvider`/`MergeMemoryToolProvider`
  defaults). `CombinedToolProvider` (`agent/tool/CombinedToolProvider.kt`) merges
  children (MCP + namespaced locals) into one run's tool set: every child MUST
  serve at least one non-blank namespace, validated (`SAFE_ID_REGEX`, no `__`)
  and unique, fail fast at construction; routing splits at the first `__`
  (unknown prefix/bare name → `isError`), `executionTimeoutSeconds` delegates to
  the owner (0 for unroutable names). Child cleanup is the DI container's job:
  the MCP provider closes its cached clients through Koin's `onClose`
  (`di/AppModule.kt`), not through this composite.
  `toolExecutionTimeoutSeconds` is REQUIRED per server (0 = none), resolved by
  the callback route from `ToolProvider.executionTimeoutSeconds`, enforced with
  `withTimeout` (overrun → `isError`, run survives). A transport failure
  mid-execution (no retry/reconnect) drops the cached client and answers an
  error tool-result; the next round's tool-list refresh is the SOLE reconnection
  point — it reconnects, or throws `McpTransportException` → `fatal` →
  `tool_transport` when the server stays down. Result attachments are
  capability-checked against the run's model.
- **Filesystem tools** (`agent/tool/filesystem/FsToolProvider.kt`, config
  `tool.fs`): a native READ-ONLY mock of the vanilla filesystem MCP server's
  read tools (that server has no read-only mode — a user who wants RW access
  uses that MCP server instead and keeps `tool.fs.enabled` false; enabling
  both fails fast at boot on the duplicate namespace). Namespace hardcoded to
  `fs` (no config field; advertised names `fs__read_text_file`,
  `fs__read_media_file`, `fs__read_multiple_files`, `fs__list_directory`,
  `fs__list_directory_with_sizes`, `fs__directory_tree`, `fs__search_files`,
  `fs__get_file_info`, `fs__list_allowed_directories` — output formats mirror
  the server's). Wired into BOTH combined tool sets (the chat loop's and the
  investigate sub-agent's, whitelistable via `agent.investigator.
  allowedNamespaces`), registered only when enabled (DI) so construction —
  canonicalizing `allowedDirs` (fail fast on missing/not-a-dir, `~`
  expanded) and compiling the `blacklists` globs — is eager at boot. Access
  control: every tool's TARGET path is canonicalized (symlink/`..`-safe) and
  checked against the canonical allowed roots + the blacklist patterns
  ([`GlobMatcher`](agent/tool/filesystem/GlobMatcher.kt), JDK
  `getPathMatcher("glob:...")` with alternates restoring minimatch's
  `**/`-matches-zero-directories rule); a refused target answers `isError`.
  Listings/search results are NOT filtered — blacklisted entries are
  returned as-is — but traversal skips entries whose canonical path leaves
  the roots (symlink-escape protection; the listings hide them, where the
  server's plain `list_directory` would show the raw link entry). An
  unreadable subtree is skipped by `search_files` but fails
  `directory_tree`, like the server. `directory_tree` is deliberately
  stricter than the server on escaping entries: one fails the whole tree —
  the server never descends a symlink and would just list it as a file
  entry. No execution budget (0).
- **Length-safe tool results** (`agent/tool/LengthSafeToolProvider.kt`,
  config `agent.main.toolResultLimit` + `agent.investigator.toolResultLimit`):
  a `ToolProvider` decorator capping the merged text of every SUCCESSFUL
  tool result (a safety net against a server dumping megabytes into the
  model's context — MCP servers rarely enforce their own output budgets).
  The cap is in CHARS, not tokens, by design: token estimation is
  unreliable across providers/models (tokenizers differ; no server-side
  tokenizer in the hand), a char count is deterministic and cheap.
  A result that fits the cap passes through untouched (no copy, no
  reordering); one that exceeds it has its text parts merged into a single
  part (in order, joined by newlines) and truncated, with the truncation
  marker budgeted INSIDE the cap so the merged text fits it whenever the
  cap is larger than the marker itself — a cap smaller than the marker
  (a few tens of chars, unusable for real tool results anyway) returns the
  marker alone. The cut never splits a UTF-16 surrogate pair (a dangling
  high half is dropped, leaving the prefix one unit short of the cap).
  Error results are never truncated, by design (a tool error
  is a short, concise failure description — never a content dump, servers
  return those as successful results — and the model needs it verbatim to
  recover in the next round); attachments survive in their original order,
  ahead of the merged text; `namespaces`/`specifications`/
  `executionTimeoutSeconds` and the result's `id`/`tool`/`isError`
  delegate untouched. Wired in the DI
  module around the chat loop's combined set (the persona's per-request
  `WhitelistedToolProvider` wraps the length-safe set) and around the
  investigator's whitelisted set; the `toolResultLimit` caps are REQUIRED
  positive (default 40000), fail fast at boot.
- **Compaction & memory extraction** (`agent/oneshot/compaction/`,
  `agent/oneshot/eltm/MemoryExtractionService.kt`, wired in
  `agent/persist/PersistChatService.kt`, config under `memory.*`):
  - Proactive trigger: before the round, when `currentPromptTokens(chat)` (last
    assistant message's provider-reported `meta.inputTokens` — usage REQUIRED on
    every hand response) exceeds `compactionTriggerFraction × model.contextLength`
    (per-entry config fields under `providers.<id>.llm`, e.g. 0.75–0.8 in
    `config.example.jsonc`; `agent/model/LLM.kt` owns the `[0,1]`/`>=1`
    contract; `0` disables); the not-yet-appended input
    isn't counted. Reactive fallback: EVERY hand `context_exhausted` round
    compacts and retries — no attempt cap; a compaction that fails or returns a
    non-clean summary throws and fails the run.
  - `ChatCompactionService.compactChat(fullChat, excludeLastNRound)`: sanitizes
    its input (`removeInjection`), anchors the stamped user messages (null spec),
    splits at a user-turn boundary (never splitting tool_call/tool_result pairs;
    the current run's trailing tool chain stays preserved), feeds the WHOLE chat
    (drop region + marker user message + preserved tail + final instruction) to a
    `runCollect` one-shot (no tools, ~500-word target), and replaces the drop
    region with one `CONTEXT COMPACTION: `-marked user message stamped with its
    own `createdAt`. A prior summary merges via the prompt. With fewer rounds
    than `excludeLastNRound`, the keep count shrinks — down to zero. Compacts or
    throws, history untouched: no user messages → `IllegalArgumentException`;
    failed/truncated/blank summary → `IllegalStateException`; a compactor that
    can't see the content (e.g. images + text-only model) →
    `ModelCapabilityException` — a `memory.compactModel` config error.
  - `MemoryExtractionService.processDiscardedMessages(droppedMessages)` runs on
    raw dropped messages BEFORE they're discarded: sanitizes (`removeInjection`)
    and re-anchors every user message with its own `<meta>` send time (null spec
    — the extractor is STATELESS: no current date in its input or prompt, so
    every relative date resolves against the message's own anchor). The
    **extractor** one-shot (no tools; capability-checked — a
    `memory.eltm.extractionModel` config error) returns a fact list or the
    `Nothing worth remember.` sentinel (the only skip path; blank extraction is a
    hand `empty_response` error). A failed extraction (e.g. truncated `length`)
    or one producing tool calls/no text throws and fails the run. On a
    non-sentinel extraction the **ELTM writer** runs (a `/v1/run` tool loop ≤
    `memory.eltm.maxWriterRounds`) — facts go STRAIGHT into the ELTM, no
    intermediate short-term store. Transient `upstream` failures retry with hand
    backoff; ANY terminal failure (classified hand error, exhausted retries,
    `round_limit`, `empty_response`) throws and fails the run — unwritten
    memories are never lost; whatever the writer already recorded sticks (a
    retry does not duplicate diary entries). Note dates: the writer stamps
    `event_date` with the extraction day (for compaction-triggered runs: the
    compaction day), never a later date.
  - Model resolution: `memory.compactModel`, `memory.eltm.extractionModel`, and
    `title.model` (`agent/oneshot/TitleGenerator.kt`, used by
    `POST /api/chats/{id}/title` — no per-chat lock; titles from the last stored
    history; empty chat short-circuits; a title model that can't see the history
    → 400) are REQUIRED config, resolved once at startup by the DI container
    (unknown ids fail fast — no tool-call requirement on the extractor); the
    one-shot services are constructed once and shared. A chat run's own model is
    never used for the pipeline. The ELTM models
    (`memory.eltm.extractionModel/embeddingModel/writerModel/
    rewriteModel`) are REQUIRED the same way — `memory.eltm` is mandatory for
    every deployment; the writer must support tool calls; the embedding
    entry's `dimensions` must not exceed `MAX_VECTOR_DIMENSIONS`.
    `memory.eltm.rewriteRounds` (≥ 1) and `relatedEntitiesLimit`/
    `relatedNotesLimit` (≥ 0) are REQUIRED with no defaults. The
    extraction/embedding/writer/rewrite ids fail fast at boot; the
    investigate agent's model (`agent.investigator.model`,
    `agent/oneshot/investigate/InvestigatorService.kt`, a tool loop) is
    REQUIRED and fails fast at boot the same way (must support tool calls),
    with its own round cap `agent.investigator.maxRounds` (`0` = unlimited;
    a `round_limit` stop is recovered by a no-tools summarization one-shot on
    the same model, a `context_exhausted` stop by a tool-call trace) and its
    own tool-result cap `agent.investigator.toolResultLimit`. The
    sub-agent's tool set is its OWN combined set (MCP + read-only `eltm`,
    built separately in the DI module — NOT the loop's set, which serves
    MCP + `gsg__investigate`) restricted by the REQUIRED non-empty
    `agent.investigator.allowedNamespaces` whitelist
    (validated like any tool namespace; an entry that set does not
    serve — including `gsg` itself, ruling out recursion — fails fast at
    boot via the `WhitelistedToolProvider` construction) and wrapped in
    the length-safe provider (see the tools section above for
    `LengthSafeToolProvider`).
    Writer/embedding knobs
    come from `memory.eltm.*` + `hand.*` (the embed timeout is the hand's
    `streamIdleTimeoutMs`). `title.lastNRound` (default `0`) caps history fed to
    the title model; the title generator reads the chat row exactly once, never
    the injection. Compactions emit no dedicated SSE event — the frontend
    resyncs after the run (done/error).
  - **Query rewrite** (`agent/oneshot/rewrite/QueryRewriteService.kt`, config
    `memory.eltm.rewriteModel` + `rewriteRounds`): a no-tools `runCollect`
    one-shot BEFORE the first hand round of every turn, after the injection. It
    sanitizes (`removeInjection`), clips the last `rewriteRounds` user rounds
    (`takeLastNRound`, ≥ 1 enforced by config), re-anchors/re-injects with its
    own empty spec (the loop's ELTM injection and updated flags never leak in),
    and rewrites the latest input into standalone retrieval queries; the
    `Nothing worth query.` sentinel — and a clipped chat with no user message —
    maps to `null` (no LLM call). The result feeds the ELTM injection:
    `PersistChatService` searches `searchEntities(query, relatedEntitiesLimit)` +
    `searchNotes(query, …, relatedNotesLimit)` and injects the hits under
    `<memories>` (a `0` limit = no hit; with BOTH limits `0` the rewrite one-shot
    is skipped too). Related-note subjects resolve to NAMES before rendering
    (entity: the search hit's entity, fallback `getEntity`; relationship:
    `getRelationship`'s endpoint names + verb). Capability-checked against its
    own model; the run model's capability check runs before the rewrite. A failed
    rewrite fails the run (the chat loop never stores).
- **frontend/** — Svelte 5 + Vite + TS (no Gradle build step), styled after
  llama.cpp's webui: Tailwind v4 (CSS-first, tokens in `src/app.css`, dark-only
  oklch "neutral" palette), bits-ui primitives, lucide icons, highlight.js.
  Proxies `/api` to ktor; ktor serves the API only.
  - Layout: collapsible glass sidebar (chat list + search filter + rename/delete
    dialogs, generate-title, personas + ELTM nav), centered `max-w-3xl` message
    column, floating rounded composer with circular send button (disabled while
    streaming, while the history loads, or without a selected model).
  - Mobile (below `md` = 768px, `src/lib/ui-store.svelte.ts`): the sidebar
    becomes an overlay drawer (`mobileNavOpen`, translated off-screen, scrim +
    auto-close on every navigation in `App.svelte`) opened from a mobile top
    bar above the views; the inline collapse rail is desktop-only and a first
    visit on a small screen defaults to collapsed. The composer row's
    model/persona chips shrink (`min-w-0` + tighter `max-sm:` caps) and the
    app shell is `overflow-x-hidden`. The composer's Enter inserts a newline
    on coarse pointers (soft keyboards have no Shift; `enterkeyhint` follows)
    and all interactive text fields are ≥ 16px on touch (`no-hover:text-base`)
    to defeat iOS's focus auto-zoom. The viewport meta carries
    `interactive-widget=resizes-content`; `App.svelte` additionally mirrors
    the `visualViewport` keyboard inset as a bottom padding for iOS (a no-op
    where the meta is honored). Dialog content scrolls
    (`max-h-[calc(100dvh-2rem)]`), and `.md-content` wraps unbroken strings
    (`overflow-wrap`).
  - Routing: hash-based (`src/lib/router.svelte.ts`, zero deps — the hash never
    reaches the server, so any static host works without SPA fallback; the same
    reason llama.cpp's webui uses `router: hash`). Routes: `#/chat` (home),
    `#/chat/<id>`, `#/eltm`, `#/personas`. The URL owns the active view and the open chat: an
    `App.svelte` `$effect` translates the route into `chatStore.pickChat`/
    `closeChat`; store actions that change the open chat navigate the hash
    (`navigate` updates the route state synchronously — the `hashchange` event
    would land after the effect; delete uses `replaceRoute` and the route effect
    redirects a later back/forward landing on a deleted chat's route to home).
    Chat-route changes are ignored while a run streams; the pending route applies
    when the run ends — a run that failed after the route left the chat surfaces
    its error as a toast. Views stay mounted, CSS-hidden by route, so a live
    stream and the composer draft survive tab switches. Sidebar highlights (chat
    row, ELTM/personas links) follow the route EXCEPT while a run streams: the
    streamed chat stays highlighted (mid-run chat-route changes are deferred
    until the run ends) and the ELTM/personas links stay unmarked while the
    stream is in flight, even though their views can be opened mid-run (the
    chat view stays mounted, CSS-hidden). A chat switch shows a loading
    placeholder (`chatLoading`) until the history arrives — a chat with
    messages never flashes the empty state, and the composer blocks sends
    while the placeholder is up.
  - State in `src/lib/chat-store.svelte.ts` (module-scope singleton — `$effect`
    runes NOT usable there; model-picker persistence in `App.svelte`). An
    in-flight delete locks the chat read-only via `deletingIds` (backend memory
    extraction can take minutes): dialog closes on click (fire-and-forget),
    sidebar actions and send stay disabled until the backend confirms. Transient
    action errors → global toasts (`lib/toast-store.svelte.ts`, rendered in
    `App.svelte`); contextual errors stay tied to their view (run-error banner
    `streamError`, ELTM view inline error). SSE semantics preserved verbatim:
    tool-round commits, retry wipes, DB resync on done/error/abnormal close,
    optimistic user message; a send that never stores restores the composer
    draft (prepended to anything typed in the composer during the run).
    Composer drafts are per-chat (`chatStore.drafts`, keyed by chat id —
    the composer is one always-mounted component, so without the swap a
    draft would leak across chat switches). The user's collapsible toggles
    live in `partOverridesBySignature`, keyed
    by round identity (`roundSignature`: tool calls + joined text), so they
    follow the round across the done reload and mid-run compaction shifts,
    and are cleared on chat switches. A failed reload commits the
    final round into the display, and a failed run's reload drops the
    uncommitted rounds. Terminal events
    (`done`/`error`/abnormal
    close) set `runEnding`, hiding the live block during the DB resync — no
    "Processing…" shimmer after the run completed, no failed-round partials
    flashing under the error banner. No client-side stop — the server only
    notices a disconnect on its next
    event write. Chat list, model catalog and the personas list re-fetch every
    30s and on window focus; the ELTM entity/relationship lists re-fetch on the
    same cadence only while the `#/eltm` view is visible (it stays mounted but
    CSS-hidden elsewhere — polling starts on first visit and stops when the
    view hides). Each list replaces itself only when the payload changed.
  - The persona picker (`PersonaDropdown.svelte`, next to the model picker in
    the Composer) selects the CURRENT chat's persona: a transient per-chat
    override on top of the chat's recorded `personaId`, always sent with the
    message (`SendMessageRequest.personaId` — the record syncs via the chat
    list resync after a successful run). Persona management lives in the
    `PersonaView.svelte` (`#/personas` sidebar tab, before ELTM): rows of
    id/name/namespaces (empty = all), per-row edit-prompt and edit-namespaces
    dialogs (one text input per namespace item) plus delete; the built-in
    `default` row is read-only.
  - The ELTM view (`EltmView.svelte`, route `#/eltm`) is browse-only (writes are
    LLM-driven): two sub-tabs — Entities (cards with attribute chips, counts +
    latest note, expandable to lazily fetch relationships and diary via the
    `/api/eltm` drill-down routes) and Relationships (cards with endpoint names +
    validity badge, expandable to fetch their notes). Both lists paginate via a
    load-more button (100 rows per page, oldest first); the background resync
    refetches the loaded window, so appended pages survive and a server-side
    shrink shrinks the list too.
  - User messages: plain-text pill bubbles (`whitespace-pre-wrap`); assistant:
    full-width markdown (marked + DOMPurify + highlight.js chrome via
    `lib/markdown-renderer.ts`). Reasoning/tool-call/tool-result parts in collapsible
    blocks (shimmer title while streaming). Auto-scroll pins while the user
    hasn't scrolled up (scroll-down button otherwise; switching chats re-pins).
    Dialogs replace `window.prompt`/`confirm`; model picker is a searchable chip
    dropdown; images via file picker/paste.
  - **lib layering & tooling** (keep the reactive hosts thin — logic lives in
    testable modules):
    - `lib/routes.ts` (pure hash→route mapping) vs `lib/router.svelte.ts`
      (window-touching singleton + navigate/replaceRoute helpers); route
      changes flow through `parseHash`, unit-tested.
    - `lib/display.ts`: pure display-assembly helpers — `roundSignature`,
      `partOrdinalKey`, `messageSpacing`, `dataUrlToImagePart` (mirrors the
      backend's data-URL regex). Imported by MessageItem/MessageList/
      ChatStore.
    - `lib/stream-session.ts`: the chat run loop extracted from ChatStore's
      old inline `send()` loop. `StreamSession.run()` never throws; it owns
      event order and terminal recovery, and drives the store through a 1:1
      verb interface (`RunHost`) plus injectable transport/reload/resync
      (`RunEnvironment`) — scripted-SSE unit tests cover batching, retry
      wipes, done/error/abnormal-close and transport-failure paths. The SSE
      parser in `api.ts` normalizes CRLF delimiters before block splitting.
    - `lib/paging.ts`: ELTM windowing math (`fetchWindow` capped-chunk walk +
      probe row, `fetchMore`), server cap mirrored as `LIST_LIMIT_CAP = 500`
      (= WebServer.kt MAX_ELTM_PAGE_LIMIT). EltmView collapses prune BOTH the
      expanded-flag map and its cached detail payload.
    - `lib/markdown-renderer.ts`: lazy pipeline facade — marked/hljs/KaTeX/
      DOMPurify + their CSS load behind `getMarkdownRenderer()` on first
      render (the home screen no longer pays for them; ~64% smaller critical
      chunk). MarkdownContent applies renders with a sequence counter so an
      in-flight init cannot overwrite a newer html state.
    - Shared ui primitives: `ui/icon-button.svelte` (square utility button),
      `ui/confirm-dialog.svelte` (destructive confirm scaffold with `busy`;
      used by DeleteChatDialog/TruncateMessagesDialog/PersonaView delete),
      `ui/dropdown-styles.ts` (bits-ui trigger/content/item class strings).
    - Composer attachments: per-file 8 MB client-side cap with a toast;
      oversized images are canvas-downscaled to a 1568 px longest edge
      (JPEG re-encode, PNG keeps alpha) — at-or-under-edge files keep their
      original bytes (animated GIFs untouched). The budget applies to the
      DOWNSCALED OUTPUT too: a still-oversized result flattens PNG alpha onto
      white and steps JPEG quality down, refusing the attachment entirely if
      nothing fits; results decoded after a chat switch park on that chat's
      persisted draft (a deleted chat's are dropped).
    - History-edit serialization: `truncatingIds` joins `deletingIds`/
      `forkingIds`; MessageItem action buttons disable on any of them. A
      guarded edit answers an "A history edit is in progress" toast instead of
      silently doing nothing (`send` returns early; truncate's dialog stays
      open).
    - Tooling: Prettier + ESLint 10 flat config (`.prettierrc.json`,
      `eslint.config.js`) wired into `npm run lint`; `npm run check` =
      lint + svelte-check, `npm run test` = Vitest (node env, colocated
      `*.test.ts`). `engines.node` pins Vite 8's requirement.

## Verification commands

```bash
./gradlew test
cd hand-pi && npm test && npm run build && npm run typecheck && npm run lint
cd frontend && npm run check && npm run build && npm test
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
