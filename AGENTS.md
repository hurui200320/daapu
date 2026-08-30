# AGENTS.md

Guidance for AI agents (and humans) working in this project.

## Project

PoC of a chatbot with a memory system: Kotlin/JVM (Gradle) brain + Svelte
frontend + Node/TS "hand-pi" service.

### Behavioral Guidelines: Micro-Sessions & Memory Isolation

- **One topic per session:** open a new chat per task/topic — prevents
  attention decay, hallucination snowballing, token cost.
- **Task-oriented & extract:** truncate/delete the chat when done — triggers
  memory extraction (`MemoryExtractionService`) into the ELTM (External
  Long-Term Memory).
- **Memory isolation:** no global shared short-term memory; context pulled
  on-demand via the ELTM and `QueryRewriteService`.

### Architecture

- **PostgreSQL + pgvector** — Exposed access; Flyway schema in
  `src/main/resources/db/migration/`.
- **hand-pi** (`hand-pi/`, `@earendil-works/pi-ai` 0.84.1) — stateless,
  opinionless LLM execution (streaming, dialects, tool-call accumulation,
  retries, usage); no catalog/sessions/prompts. Kotlin owns all content
  (history, prompts, injection, compaction, extraction, tools, memory,
  persistence) via `hand/HandService.kt` over `hand/HandClient.kt`'s
  `/v1/run` SSE round loop; ONE loop, retry policy, and classification
  system-wide.
  - Every LLM call is a `/v1/run`: `run` streams `[HandEvent]`;
    `runCollect` (one-shots) collects to a terminal `List<ChatMessage>`
    (text one-shots take the last); `runCollectPartial` returns messages
    plus the terminal `HandRunException` instead of throwing (dropped
    transport still throws `HandUpstreamException`) — the investigator's
    recovery hook.
  - Per run: fresh internal `runId` (registered pre-request; duplicate
    fails fast; evicted at stream end; never seen by the chat loop); tool
    callback URL attached per request (the hand POSTs only when a tool
    executes).
  - Tool ads per-round: the hand GETs `{toolListUrl}?runId=...`
    (`GET /api/hand/tools`, `server/endpoint/HandRoute.kt`) BEFORE EVERY
    LLM request, so MCP servers can change tools mid-session; a failed
    list ends the run `tool_transport` (same as callback `fatal`). The
    list feeds `tools` only; execution budgets never leave the brain.
    A tool-LESS run (`EmptyToolProvider` — the one-shots, the import
    script) sends NEITHER tool URL (nulls are omitted on the wire), so
    the hand makes no brain-side HTTP call at all — no tool-list GET, no
    callback (a stray model tool call fails the run `internal`). It
    needs no HTTP server next to it.
    Execution calls back `POST /api/hand/tool`, resolved by `runId` in
    `hand/HandCallbackService.kt`; a round's tool calls execute IN
    PARALLEL, results reassemble in call order. The callback has no
    hand-side deadline: the brain always answers (it enforces budgets), a
    disconnect aborts, a crash → `tool_transport`.
  - **Embeddings** (`/v1/embed`, `hand-pi/src/embed.ts`): plain-JSON
    sibling of `/v1/run`; one OpenAI-compatible `{baseUrl}/embeddings`
    call per request (`model.baseUrl/apiKey/modelId`, `dimensions`,
    `input`, `maxRetries`, `timeoutMs`, optional `additionalProperties`
    merged into the gateway body, e.g. deepinfra `service_tier`;
    collisions with hand-managed keys → `invalid_request`; no hand
    defaults). Statuses: `invalid_request` → 400, `auth` → 401,
    `upstream` → 502 (5xx/429/network/timeout + endpoint 404/405
    transient, retried; `maxRetries` 0 = unlimited); the response MUST
    carry one vector per input, realigned by `index`
    (collapse/duplicate/gap/miss → `upstream`). Kotlin:
    `agent/model/EmbeddingModel.kt` (catalog `providers.<id>.embedding`),
    `hand/HandClient.embed` → `EmbeddingException`,
    `hand/HandService.embed` (transport → `EmbeddingException("upstream")`;
    dimensions ≠ catalog fails fast).
- **ktor HTTP API** (`server/`) — `Main.kt` loads `config.jsonc`
  (`config/Config.kt`), starts DB + API. One chat run per request:
  `ChatService.prepareRun` validates (model AND personaId REQUIRED per
  message, no server default); `runChat` runs the turn loop
  (`agent/persist/PersistChatService.kt`). Catalog
  (`agent/ModelCatalog.kt`, from `providers.<id>.llm/embedding`; ≥1 LLM
  entry; duplicate composite ids fail fast) and chat store built once,
  shared. System prompt rendered per run, travels out of band (no
  `system` role, never stored). SSE from `server/WebServer.kt` (incl.
  `tool_call`/`tool_result` echoes), flushed with a leading `comment`
  event — Netty's 10s `responseWriteTimeoutSeconds` would kill a silent
  run.
  - **Personas** (`agent/persona/`): prompt = user-managed persona
    (identity/personality/policy + namespace whitelist) + code-owned GSG
    harness introduction
    (`agent/persist/MainAgentSystemPromptService.kt`), gated on the
    whitelist serving `gsg`: with it (or empty = all) the full
    introduction; without it a reduced `# Context` section + time-only
    injection (no `eltm-updated`/`<memories>`; rewrite + ELTM searches
    skipped). DEFAULT persona (reserved id 0) is code-only, never a row
    (API rejects mutations); empty whitelist = all namespaces. Rows
    `(id, name, system_prompt, allowed_namespaces)` (BIGSERIAL id as
    number; 0 never collides). Whitelist JSON array validated at save
    against the loop's boot snapshot (`PersonaService`; `[]` = all;
    unserved namespace incl. `eltm` → 400). The request owns the persona:
    `SendMessageRequest.personaId` resolved in `prepareRun` (unknown →
    400, no fallback), wrapping the loop's tools in a per-request
    `WhitelistedToolProvider` (empty = unfiltered; unservable entry → 400
    pre-stream). `chats.persona_id` is a record only (starts 0, inherited
    by forks, stamped by the successful store; never consulted). Routes
    `GET/POST /api/personas`, `PUT/DELETE /api/personas/{id}` (400 on id
    0), `server/endpoint/PersonasRoute.kt`.
  - **DI** (`di/AppModule.kt`, Koin 4.2 + compiler plugin): one
    `module { }`, all definitions `single` via the plugin DSL —
    compile-time graph checks. `ChatService` is pure constructor
    injection. One-shot models resolve inline via `requiredLlm(...)`
    (fail-fast; investigator's model at boot, wired via the loop's
    `gsg__investigate`). Cleanup: Koin `onClose` on
    `HandService`/`McpToolProvider` at JVM shutdown (`koinApp.close()`).
    `startWebServer` resolves the root BEFORE server start (fail-fast +
    eager MCP connect). Tests: `testutil/TestDi.kt`
    `testKoinApp`/`chatService` with optional overrides — the stores default
    to the PRODUCTION Postgres implementations over a throwaway
    testcontainers test database (`testutil/TestDb.kt`, no in-memory fakes);
    `assertFailsFast` unwraps `InstanceCreationException`.
  - **ELTM** (`memory/eltm/EltmService.kt` + `PostgresEltmService.kt`,
    diary model): entities `(id, name, category)` + attributes
    `(entity_id, key, value)` (current-state, one row per (entity, key))
    and relationships `(src, verb, dst, valid)` hold NO content; all
    content lives in **notes** — add-only dated entries `(subject,
    event_date, note)`, strictly single-subject (entity XOR relationship,
    migration CHECK). `valid=false` = soft delete; re-establishing an
    ended triple is a diary event. No mention counter — prominence
    computed on read.
    - Write path = extraction only: facts → ELTM writer agent
      (`agent/pipeline/eltm/EltmWriterService.kt` + 13-tool
      `memory/eltm/EltmToolProvider.kt`, RW; `readOnly` mode = the 5 read tools for
      the investigator; `runCollect` loop, `memory.eltm.writerModel`, cap
      `maxWriterRounds`). Writer failure fails the run; retry re-extracts
      (recorded content is skipped). Exact-match creates are pure reads;
      renames via `refine_entity` (id kept, attachments stay; identical
      (name, category) = no-op, a collision errors → model merges); unique
      violations re-select the winner; `merge_entities` folds colliding
      triples (notes re-pointed BEFORE delete; re-pointed self-loops
      invalidated) and folds attributes (winner wins); relationship
      endings are diary events (`add_relationship_note` `valid` =
      structural change + note in ONE transaction, idempotent); exactly
      ONE `eltm_relationships` row per triple (full unique index). Read
      tools fail fast on nonexistent subjects/malformed dates. Attribute
      writes re-embed the entity (`name + category` + alphabetical
      `key: value` lines); an embedding `invalid_request` answers an
      `isError` "split it into several smaller notes and retry." tool
      result.
    - Read path: `readOnly` (`eltm` namespace) combines with MCP into the
      investigator's OWN tool set (second `CombinedToolProvider` in DI —
      not the loop's); the chat loop reaches the ELTM only via
      `gsg__investigate` (`agent/persist/GsgToolProvider.kt`).
    - `chats.eltm_version` ("" = first run flags) = global write counter
      (`EltmService.version()`): every visible-state write bumps it via
      atomic `value = value + 1` in the write's own transaction (never a
      no-op touch); the persist loop reads it AFTER pre-round compaction,
      compares at both injection sites (`eltmUpdated`), stamps on the
      successful store — a failed run never moves it.
    - Vector columns FIXED `vector(2000)` (pgvector HNSW limit;
      `MAX_VECTOR_DIMENSIONS` in `config/Config.kt`,
      `db/VectorColumnType.kt`): model dims
      (`memory.eltm.embeddingModel`, ≤ 2000, fail fast) pick the nonzero
      prefix; vectors/queries zero-padded to 2000 (`padVector`); cosine is
      invariant — model switches need no schema change. Similarity via
      Exposed pgvector (`VectorDistance` COSINE).
  - **Context injection** (`agent/context/ContextInjection.kt`): user
    messages carry stored `createdAt` (UTC Instant; required by
    `ChatCodec.validateChat` + `PostgresChatStore.store`).
    `injectContext` prepends `<meta><sent-at>` anchors to historical user
    messages (server's CURRENT zone) and, chat loop only, the full
    `<injection>` on the latest user message; one-shots pass null spec
    (anchors only). `removeInjection` strips both before every store —
    stored chats never carry harness XML. Guarded: `<meta>` recognized
    only on byte-match with its deterministic render; `<injection>` via
    two XSDs (`injectionSchema.xsd` full, `injectionSimpleSchema.xsd`
    time-only — a hybrid validates against neither and survives as user
    content). `<memories>` carries the ELTM injection
    (`<related-entities>`/`<related-notes>`, possibly empty; searched by
    the rewritten query): `<entity id name category>` + `<attribute key>`,
    `<note id date subject-type>` identified by names (`RelatedNoteView`);
    the XSD declares the subject attributes as the UNION of both shapes
    (XSD 1.0 can't XOR), the generator emits exactly one set — forged
    injections rejected. Spec fields all-null together or all non-null
    (mixed rejected at construction). Compaction/extraction/rewrite
    sanitize then re-anchor, never double-injecting; `TitleGenerator`
    needs neither.
  - **Locks**: per-chat `Mutex` guards concurrent runs (409) and deletes
    (`store` is an upsert — deleting mid-run would resurrect the row).
    `deleteChat` runs extraction over the full history BEFORE deleting,
    holding the lock; failed extraction fails the delete (retry
    re-extracts). Entries via `ConcurrentHashMap.compute`, evicted on
    completion/delete.
  - **ChatStore** (`agent/chat/ChatStore.kt`): all `chats` access behind
    it; `ChatService` holds no raw DB calls. `load` → full
    `ChatEntry`; `ChatInfo` is the wire shape only. `renameChat`/
    `generateTitle` take no lock.
  - **History mutation by message INDEX** (no message ids):
    - `DELETE /api/chats/{id}/messages/{index}` (`truncateChat`): drops
      the user message at index and everything after — WITHOUT
      extraction — resets `eltm_version` to `""`, persona untouched,
      takes the lock; 400 on non-user/out-of-bounds index or one ending
      the chat mid-turn.
    - `POST /api/chats/{id}/fork/{index}` (`forkChat`): copies history
      through the assistant message at index (`finishReason` "stop") into
      a new row — no lock; the fork's `eltm_version` starts `""`.
    - Both validate via `ChatCodec.validateChat`; frontend reveals
      actions on hover (trash on user, fork on assistant stop), hides
      them while streaming, confirms truncation in a dialog.
- **MCP tool servers** (`mcp/`, config `mcp.*`;
  `io.modelcontextprotocol:kotlin-sdk-client` 0.15.0, streamable-HTTP +
  stdio): `McpToolProvider` implements `agent/tool/ToolProvider.kt`,
  namespacing `{namespace}__{tool}`; empty namespace set = one-shot shape
  (bare names, `EltmToolProvider`/`MergeMemoryToolProvider` defaults).
  `CombinedToolProvider` merges children: every child MUST serve ≥1
  non-blank unique namespace (`SAFE_ID_REGEX`, no `__`, fail fast);
  routing splits at the first `__` (unknown → `isError`);
  `executionTimeoutSeconds` delegates to the owner; child cleanup is the
  DI container's job. `toolExecutionTimeoutSeconds` REQUIRED per server
  (0 = none), enforced with `withTimeout` (overrun → `isError`, run
  survives). Mid-execution transport failure drops the cached client,
  answers an error tool-result; the next round's tool-list refresh is the
  SOLE reconnection point — or `McpTransportException` → `fatal` →
  `tool_transport`. Result attachments are capability-checked.
- **Filesystem tools** (`agent/tool/filesystem/FsToolProvider.kt`, config
  `tool.fs`): READ-ONLY mock of the vanilla filesystem MCP server's read
  tools (RW = use that MCP server; enabling both fails fast on the
  duplicate namespace). Namespace `fs`; names `fs__read_text_file`,
  `fs__read_media_file`, `fs__read_multiple_files`, `fs__list_directory`,
  `fs__list_directory_with_sizes`, `fs__directory_tree`,
  `fs__search_files`, `fs__get_file_info`,
  `fs__list_allowed_directories` (formats mirror the server). Wired into
  BOTH tool sets (whitelistable via
  `agent.investigator.allowedNamespaces`), eager at boot (canonicalizing
  `allowedDirs` fail-fast, compiling `blacklists` globs). TARGET paths
  canonicalized (symlink/`..`-safe), checked against canonical roots +
  blacklist globs (`GlobMatcher`, JDK glob with minimatch's
  `**/`-zero-dirs rule); refused → `isError`. Listings/search NOT
  filtered; traversal skips entries escaping the roots. Unreadable
  subtree: `search_files` skips, `directory_tree` fails (like the server;
  stricter on escaping entries — one fails the whole tree). No execution
  budget (0).
- **Length-safe tool results** (`agent/tool/LengthSafeToolProvider.kt`,
  `agent.main.toolResultLimit` + `agent.investigator.toolResultLimit`):
  decorator capping the merged text of every SUCCESSFUL tool result. Cap
  in CHARS not tokens (deterministic). Fits → untouched; over → text
  parts merged (in order, newline-joined) + truncated, marker INSIDE the
  cap (cap < marker → marker alone); never splits a UTF-16 surrogate.
  Errors never truncated; attachments keep order ahead of text; other
  metadata delegates. Around the loop's set (persona whitelist wraps it)
  and the investigator's set; caps REQUIRED positive (default 40000).
- **Compaction & memory extraction** (`agent/pipeline/compaction/`,
  `agent/pipeline/eltm/MemoryExtractionService.kt`, wired in
  `PersistChatService`, config `memory.*`):
  - Proactive trigger: `currentPromptTokens(chat)` (last assistant's
    `meta.inputTokens` — usage REQUIRED on every hand response) exceeds
    `compactionTriggerFraction × model.contextLength` (per-entry fields,
    e.g. 0.75–0.8; `agent/model/LLM.kt` owns the `[0,1]`/`>=1` contract;
    `0` disables); pending input not counted. Reactive: EVERY
    `context_exhausted` round compacts + retries — no cap; failure fails
    the run.
  - `compactChat(fullChat, excludeLastNRound)`: sanitizes, re-anchors,
    splits at a user-turn boundary (tool pairs intact; trailing tool
    chain preserved), feeds the WHOLE chat to a `runCollect` one-shot
    (~500-word target), replaces the drop region with one
    `CONTEXT COMPACTION: ` user message stamped `createdAt`; prior
    summary merges; keep count shrinks to zero if needed. History
    untouched on throw: no user messages → `IllegalArgumentException`;
    failed/blank summary → `IllegalStateException`; blind compactor →
    `ModelCapabilityException` (`memory.compactModel` config error).
  - `processDiscardedMessages`: sanitize + re-anchor each user message
    with its own `<meta>` (extractor STATELESS — dates resolve
    per-message). Extractor one-shot (no tools, capability-checked) →
    facts or `Nothing worth remember.` (only skip; blank =
    `empty_response` error); failed extraction or tool calls/no text
    fails the run. Non-sentinel → ELTM writer (tool loop ≤
    `maxWriterRounds`) — facts go straight to the ELTM, no intermediate
    store. `upstream` retries with hand backoff; ANY terminal failure
    fails the run (nothing lost; recorded content sticks — no duplicate
    diary entries). `event_date` = extraction (or compaction) day, never
    later.
  - Models: `memory.compactModel`, `memory.eltm.extractionModel/
    embeddingModel/writerModel/rewriteModel`, `agent.investigator.model`,
    and `title.model` are ALL REQUIRED, resolved once at boot by DI
    (unknown ids fail fast; one-shot services constructed once, shared; a
    chat run's model never used for the pipeline); `memory.eltm` is
    mandatory. Constraints: writer + investigator must support tool
    calls; embedding `dimensions` ≤ `MAX_VECTOR_DIMENSIONS`;
    `rewriteRounds` ≥ 1; `relatedEntitiesLimit`/`relatedNotesLimit` ≥ 0;
    writer/embedding knobs from `memory.eltm.*` + `hand.*` (embed
    timeout = `streamIdleTimeoutMs`).
  - `TitleGenerator` (`POST /api/chats/{id}/title`): no lock; reads the
    last stored history once, never the injection; empty chat
    short-circuits; a model that can't see the history → 400;
    `title.lastNRound` (default 0) caps history fed.
  - Investigator (`agent/pipeline/investigate/InvestigatorService.kt`,
    tool loop): REQUIRED at boot; round cap
    `agent.investigator.maxRounds` (0 = unlimited; `round_limit` stop
    recovered by a no-tools summarization one-shot, `context_exhausted`
    by a tool-call trace); tool-result cap
    `agent.investigator.toolResultLimit`. Tool set = OWN combined set
    (MCP + read-only `eltm` — not the loop's, which serves MCP +
    `gsg__investigate`) restricted by REQUIRED non-empty
    `agent.investigator.allowedNamespaces` (unservable entries incl.
    `gsg` — no recursion — fail fast), wrapped length-safe. Compactions
    emit no SSE event — the frontend resyncs.
  - **Query rewrite** (`agent/pipeline/rewrite/QueryRewriteService.kt`,
    `memory.eltm.rewriteModel` + `rewriteRounds`): no-tools `runCollect`
    before the first round of every turn, after injection. Sanitizes,
    clips the last `rewriteRounds` user rounds (`takeLastNRound`),
    re-injects with its own
    empty spec (the loop's injection never leaks in), rewrites the latest
    input into standalone retrieval queries; `Nothing worth query.` / no
    user message → `null` (no LLM call). Result feeds `<memories>` via
    `searchEntities` + `searchNotes` (0 limit = no hit; both 0 → rewrite
    skipped). Note subjects resolve to NAMES before rendering.
    Capability-checked (run model first); a failed rewrite fails the run.
- **frontend/** — Svelte 5 + Vite + TS (no Gradle), styled after
  llama.cpp's webui: Tailwind v4 (CSS-first, tokens in `src/app.css`,
  dark-only oklch), bits-ui, lucide, highlight.js. Proxies `/api` to
  ktor; ktor serves API only.
  - Layout: collapsible glass sidebar (chat list + search + rename/delete
    dialogs, generate-title, personas + ELTM nav), centered `max-w-3xl`
    column, floating rounded composer (send disabled while streaming,
    history loading, or no model).
  - Mobile (<768px `md`, `src/lib/ui-store.svelte.ts`): sidebar = overlay
    drawer (`mobileNavOpen`, scrim + auto-close on navigation); collapse
    rail desktop-only, first small-screen visit defaults collapsed;
    composer chips shrink; Enter = newline on coarse pointers
    (`enterkeyhint` follows); touch text fields ≥ 16px (no iOS focus
    zoom); viewport meta `interactive-widget=resizes-content` +
    `visualViewport` inset mirrored as bottom padding; dialogs scroll;
    `.md-content` wraps unbroken strings.
  - Routing: hash-based, zero deps (`src/lib/router.svelte.ts`) — static
    hosts need no SPA fallback. Routes `#/chat`, `#/chat/<id>`, `#/eltm`,
    `#/personas`. The URL owns the view and open chat: an `App.svelte`
    `$effect` maps route → `pickChat`/`closeChat`; store actions navigate
    the hash (`navigate` updates synchronously; delete uses
    `replaceRoute` + redirects stale back/forward landings home).
    Chat-route changes ignored mid-run, applied at run end (failed runs
    toast). Views stay mounted, CSS-hidden — streams and drafts survive
    tab switches. Sidebar highlights follow the route EXCEPT mid-run
    (streamed chat stays highlighted; ELTM/personas links unmarked).
    Chat switch shows the `chatLoading` placeholder (no empty-state
    flash; sends blocked).
  - State (`src/lib/chat-store.svelte.ts`, module-scope singleton — no
    `$effect` runes there; model-picker persistence in `App.svelte`):
    in-flight delete locks the chat read-only via `deletingIds`
    (extraction can take minutes; dialog fire-and-forget; actions/send
    disabled until confirmed). Transient errors → toasts
    (`lib/toast-store.svelte.ts`); contextual errors stay view-tied
    (`streamError`, ELTM inline). SSE semantics verbatim: tool-round
    commits, retry wipes, DB resync on done/error/abnormal close,
    optimistic user message; a never-stored send restores the draft
    (prepended to anything typed mid-run). Per-chat drafts
    (`chatStore.drafts`). Collapsible toggles in
    `partOverridesBySignature` keyed by `roundSignature` (calls + text),
    following rounds across reloads/compaction, cleared on chat switch.
    Failed reload commits the final round; failed run's reload drops
    uncommitted rounds. Terminal events set `runEnding` (no shimmer, no
    partials under the banner). No client-side stop — the server notices
    a disconnect on its next event write. Chat list/model catalog/personas
    re-fetch every 30s + focus; ELTM lists only while `#/eltm` is
    visible; lists replace only on payload change.
  - Persona picker (`PersonaDropdown.svelte`, next to the model picker):
    transient per-chat override on the recorded `personaId`, always sent
    (`SendMessageRequest.personaId`; record syncs via list resync).
    Management in `PersonaView.svelte` (`#/personas`): id/name/namespaces
    rows, edit-prompt/edit-namespaces dialogs, delete; built-in `default`
    read-only.
  - ELTM view (`EltmView.svelte`, `#/eltm`): browse-only (writes are
    LLM-driven) — Entities (attribute chips, counts + latest note, lazy
    relationship/diary drill-downs via `/api/eltm`) and Relationships
    (names + validity badge, lazy notes). Load-more pagination (100
    rows/page, oldest first); resync refetches the loaded window
    (appends survive, server-side shrinks shrink).
  - Messages: user = plain-text pills (`whitespace-pre-wrap`); assistant
    = full-width markdown (marked + DOMPurify + highlight.js via
    `lib/markdown-renderer.ts`). Reasoning/tool parts in collapsible
    blocks (shimmer while streaming). Auto-scroll pins until scroll-up
    (scroll-down button; chat switch re-pins). Dialogs replace
    `prompt`/`confirm`; searchable model dropdown; images via file
    picker/paste.
  - **lib layering & tooling** (thin reactive hosts; logic in testable
    modules):
    - `lib/routes.ts` (pure hash→route) vs `lib/router.svelte.ts` (window
      singleton + navigate/replaceRoute); `parseHash` unit-tested.
    - `lib/display.ts`: pure display helpers (`roundSignature`,
      `partOrdinalKey`, `messageSpacing`, `dataUrlToImagePart` — mirrors
      the backend's data-URL regex).
    - `lib/chat-logic.ts`: pure decisions (`effectivePersonaId`,
      `computeUsage`, `applyToolResult`/`commitRoundParts` — returns
      whether buffers must be wiped, `runFailureText`); unit-tested.
    - `lib/paged-tab.svelte.ts`: ELTM tab state machine (`PagedTab`) —
      paged window, expand flags + lazy drill-downs, resync re-arming
      `full` via the probe; unit-tested.
    - `lib/stream-session.ts`: the run loop — `StreamSession.run()` never
      throws; owns event order and terminal recovery via `RunHost` verbs
      + injectable `RunEnvironment`; scripted-SSE tests cover batching,
      retry wipes, done/error/abnormal-close, transport failure. The SSE
      parser in `api.ts` normalizes CRLF.
    - `lib/paging.ts`: ELTM windowing (`fetchWindow` capped-chunk walk +
      probe, `fetchMore`); `LIST_LIMIT_CAP = 500` (= WebServer.kt
      MAX_ELTM_PAGE_LIMIT); collapses prune expand flags AND cached
      payloads.
    - `lib/markdown-renderer.ts`: lazy pipeline facade (marked/hljs/
      KaTeX/DOMPurify + CSS on first render); sequence counter prevents
      stale overwrites; live throttle scales with buffered length (capped
      1 s); tabnabbing hook installs once.
    - Shared ui: `ui/icon-button.svelte` (+ `iconButtonClass`),
      `ui/confirm-dialog.svelte` (DeleteChat/TruncateMessages/
      PersonaView), `ui/dropdown-styles.ts`, `ui/message-styles.ts`
      (`lightboxTriggerBtn`/`toolPreBlock`), `lib/focus-trap.ts`
      (`trapTab`, ImageLightbox).
    - Attachments: 8 MB per-file cap with toast; oversized images
      canvas-downscaled to 1568 px longest edge (JPEG re-encode, PNG
      keeps alpha; GIFs untouched); budget applies to the downscaled
      output (alpha flatten + JPEG quality steps, refuse if nothing
      fits); decoded results park on the persisted draft (deleted chats'
      dropped).
    - History-edit serialization: `truncatingIds` joins `deletingIds`/
      `forkingIds`; buttons disable on any; guarded edits toast "A
      history edit is in progress" (`send` returns early; truncate's
      dialog stays open).
    - Tooling: Prettier + ESLint 10 flat config → `npm run lint`;
      `npm run check` = lint + svelte-check; `npm run test` = Vitest
      (node env, colocated `*.test.ts`); `engines.node` pins Vite 8's
      requirement.

## Verification commands

```bash
./gradlew test   # starts its own testcontainers PostgreSQL; Docker must be available
cd hand-pi && npm test && npm run build && npm run typecheck && npm run lint
cd frontend && npm run check && npm run build && npm test
```

Run them after any relevant source change. They must exit clean. The JVM
tests run the production stores against a throwaway testcontainers
PostgreSQL (`testutil/TestDb.kt`, one `pgvector/pgvector:pg18-trixie`
container — the same tag `compose.yaml`'s dev database uses — per test
JVM): Docker must be reachable — without it the tests FAIL FAST
with a clear error, never silently skip.

## Code quality and style rules

These sections describe the rules/items to watch out when writing or reviewing code.
When writing or reviewing code, looking for bugs with the following perspectives:

+ Bug detection and correctness: Logic errors, off-by-one mistakes, race conditions, unhandled edge cases, incorrect assumptions, regressions.
+ Test coverage and test quality: Coverage gaps, weak assertions, tautological tests, missing scenarios. Are key code paths tested? Do tests actually validate correct behavior? Are unit tests well-structured with meaningful assertions?
  JUnit Jupiter silently DROPS a `@Test` method whose Kotlin return type is
  non-`Unit`: an expression-bodied `= runBlocking { ... }` test whose last
  statement returns a value (e.g. `assertFailsWith`, `assertIs`, `assertNotNull`)
  compiles to a non-void method and is never discovered — no error, no report
  entry. Keep the last statement a `Unit` assertion (bind the value and assert
  on it), and treat a class whose executed-test count is lower than its
  `@Test` count as a bug.
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
