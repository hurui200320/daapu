# AGENTS.md

Guidance for AI agents (and humans) working in this project.

> This file acts as the TOC and a map of the repo.
> Its purpose is to help humans and LLMs quickly locate related components without exploring the whole codebase.
> It MUST NOT repeat comments from the code or explain things that are obvious by just taking a look at the code.

## Contents

- [Project](#project)
- [Repo map](#repo-map)
- [Architecture](#architecture)
- [Verification commands](#verification-commands)
- [Code quality and style rules](#code-quality-and-style-rules)
- [Backend style](#backend-style)
- [Operational rule](#operational-rule)

This file is a MAP, not a specification: for any behavioral question the
code (and its KDoc) is authoritative. README.md owns the run instructions,
the full config reference, and the API table — this file only points at
them. Where a fact lives in a file's KDoc, this map names the file instead
of restating the fact.

## Project

PoC of a chatbot with a memory system: Kotlin/JVM (Gradle) brain + Svelte
frontend + Node/TS "hand-pi" service.

### Behavioral guidelines: micro-sessions & memory isolation

The session philosophy — one topic per chat, task-oriented truncation that
feeds memory extraction into the ELTM, on-demand context instead of a shared
short-term memory — is documented once in README.md ("Design Philosophy:
One Topic Per Session"). See there; do not duplicate it here or in code.

## Repo map

Kotlin paths are relative to `src/main/kotlin/info/skyblond/daapu/` unless
prefixed otherwise; frontend paths are relative to `frontend/`.

```text
README.md                    run instructions, config reference, API table
config.example.jsonc         documented config shape — copy to config.jsonc
                             (gitignored; holds API keys)
config.schema.json           JSON Schema mirroring config/Config.kt
                             (update rule: see Code quality and style rules)
compose.yaml                 dev PostgreSQL (pgvector/pgvector:pg18-trixie)
                             + the deployment stack (hand, brain)
Dockerfile                   multi-stage: frontend dist → `frontend` classpath
                             package → Gradle installDist → zulu JDK toolbox
                             runtime (node/python for stdio MCP servers; root
                             for the brain's bash tool — installs are
                             per-container ephemeral) (.dockerignore keeps
                             config.jsonc out of every build context); dev has
                             no such package — see server/WebServer.kt
                             staticWebUi
src/main/resources/
  frontend/                  the packaged web UI (Docker-built artifact,
                             gitignored — absent in dev)
src/main/kotlin/info/skyblond/daapu/
  Main.kt                    entry point: loads config.jsonc, starts DB + API
  agent/
    ModelCatalog.kt          model catalog from providers.<id>.llm/embedding
    chat/                    ChatMessage.kt/ChatCodec.kt (wire validation),
                             ChatService.kt (API-facing chat ops incl.
                             delete/fork/truncate), ChatStore.kt +
                             PostgresChatStore.kt, ImageAttachments.kt,
                             ChatExtensions.kt (display/report text helpers)
    model/                   LLM.kt/EmbeddingModel.kt wrappers + capability
                             checks (LLMCapability.kt)
    persona/                 Persona model, PersonaService, stores; the DEFAULT
                             persona is code-only (DefaultPersona.kt)
    persist/                 the turn loop (PersistChatService.kt), the GSG
                             harness system prompt
                             (MainAgentSystemPromptService.kt), the
                             gsg__investigate tool (GsgToolProvider.kt), the
                             stream-event callback SPI
                             (StreamingExecutionCallback.kt)
    pipeline/                one-shot helpers (OneShotRuns.kt,
                             TitleGenerator.kt) + sub-pipelines: compaction/,
                             eltm/ (MemoryExtractionService.kt +
                             EltmWriterService.kt), investigate/, rewrite/
    context/                 ContextInjection.kt + RelatedNotes.kt
                             (XSD-guarded harness XML)
    tool/                    ToolProvider.kt SPI (+ EmptyToolProvider);
                             Namespaces.kt (the {namespace}__{tool}
                             contract), ToolArgs/ToolResults/ToolSchemas;
                             Combined/Whitelisted/LengthSafe decorators;
                             filesystem/ (read-only fs mock, GlobMatcher)
  config/                    config models, one file per section (Config.kt
                             is the root)
  db/                        Exposed tables (Tables.kt), advisory chat locks
                             (AdvisoryChatLockManager.kt),
                             VectorColumnType.kt, MetaCounter.kt, Database.kt
                             (pool/Flyway init, the withTransaction seam),
                             ChatIds.kt, SqlErrors.kt
  di/                        the Koin module (AppModule.kt)
  hand/                      the hand-pi client: HandService.kt (the ONE
                             /v1/run loop), HandClient.kt (SSE + /v1/embed),
                             HandCallbackService.kt (tool-callback resolution),
                             HandRunPolicy.kt (the shared retry/idle-timeout
                             budget), HandDtos.kt + HandMappers.kt (wire
                             mapping), HandErrors.kt (the error families)
  mcp/                       MCP tool servers (McpToolProvider.kt,
                             ClientEntry.kt, McpExceptions.kt)
  memory/eltm/               ELTM service + Postgres impl (EltmService.kt,
                             PostgresEltmService.kt), EltmToolProvider.kt,
                             the background extraction queue
                             (ExtractionQueue.kt + ExtractionQueueWorker.kt)
  script/                    RefreshEmbedding.kt — one-off
                             embedding-model-switch maintenance (see its KDoc)
  server/                    ktor HTTP API: WebServer.kt, SseEvents.kt (SSE
                             event mapping), Dtos.kt, endpoint/ (Chats/
                             Models/Personas/Eltm/Hand routes, FailureChain.kt,
                             Params.kt)
src/main/resources/
  db/migration/              Flyway schema: V1__init.sql, V2__personas.sql,
                             V3__pending_extractions.sql
  agent/                     XSD guards: injectionSchema.xsd,
                             injectionSimpleSchema.xsd, metaSchema.xsd
src/test/kotlin/             JVM tests — see Verification commands
                             (testutil/TestDb.kt)
hand-pi/                     stateless Node/TS LLM execution service (the
                             "hand"); wire types in src/types.ts, golden
                             fixture test/fixtures/chat-golden.json; its own
                             Dockerfile + .dockerignore
frontend/                    Svelte 5 SPA
```

## Architecture

Component map: each entry gives the role and only the invariants that span
several components; semantics, edge cases, and error paths live in the
KDoc of the named files.

- **Brain vs hand.** Kotlin owns ALL content (history, prompts, injection,
  compaction, extraction, tools, memory, persistence);
  `hand/HandService.kt` + `hand/HandClient.kt` drive the stateless,
  opinionless `hand-pi/` service (`@earendil-works/pi-ai` — no catalog/
  sessions/prompts). ONE `/v1/run` SSE round loop, retry policy, and
  classification system-wide (the retry/idle-timeout budget:
  `hand/HandRunPolicy.kt`) — every LLM call (chat loop, one-shots,
  pipelines) goes through it. Tool-less runs use `EmptyToolProvider`
  (in `agent/tool/ToolProvider.kt`) and attach no tool URLs at all.
  Embeddings are the plain-JSON sibling `/v1/embed`
  (`hand-pi/src/embed.ts`, `HandClient.embed`). The wire contract is owned
  by Kotlin — see [Backend style](#backend-style).
- **HTTP API** (`server/`): one chat run per request —
  `ChatService.prepareRun` validates message/model/persona, then
  `agent/persist/PersistChatService.kt` runs the turn loop, which rejects
  content the run model cannot process before the first LLM round
  (per-attachment capability gating: `AttachmentKind.requiredCapabilities`
  in `agent/chat/ChatMessage.kt`, enforced by
  `LLM.checkPromptContentCapabilities`; tool-result attachments fail
  `fatal` via `hand/HandCallbackService.kt`). The chat SSE
  stream sends a leading `comment` event before any LLM work (why: see
  `server/endpoint/ChatsRoute.kt`);
  event payloads are mapped in `server/SseEvents.kt` and parsed by
  `frontend/src/lib/api.ts`.
  The full endpoint table is in README.
- **Personas** (`agent/persona/`): the system prompt = user-managed persona
  + code-owned GSG harness introduction
  (`agent/persist/MainAgentSystemPromptService.kt`). The DEFAULT persona
  (reserved id 0) is code-only and never a row; the request's `personaId`
  is resolved per run and wraps the loop's tools in a per-request
  `WhitelistedToolProvider`; a whitelist that serves the reserved `gsg`
  namespace gates the whole harness — the `gsg__investigate` tool, the full
  harness introduction and the `<memories>` injection (`Persona.serves`);
  `chats.persona_id` is a record only. Routes:
  `server/endpoint/PersonasRoute.kt`.
- **DI** (`di/AppModule.kt`): one Koin module; all pipeline models resolve
  ONCE at boot and are never a chat run's model (boot-time eager-resolution
  and fail-fast mechanics: the AppModule KDoc); cleanup via Koin `onClose`.
- **ELTM** (`memory/eltm/`): the external long-term memory (a diary model;
  schema and invariants: `EltmService.kt`, `db/migration/V1__init.sql`).
  Write path = extraction only (below); the chat loop reads the ELTM only
  through `gsg__investigate`; the investigator gets the read-only tool
  subset. The global write counter (`memory_meta_number.eltm_version`,
  `db/MetaCounter.kt`, read via `EltmService.version()`) drives the
  injection decision; the persist loop stamps its value into the per-chat
  `chats.eltm_version` fingerprint only on the successful store. Vector
  column encoding: `db/VectorColumnType.kt` (with `MAX_VECTOR_DIMENSIONS`
  in `config/Config.kt`).
- **Context injection** (`agent/context/ContextInjection.kt`):
  deterministic `<meta>` sent-at anchors on user messages plus the full
  `<injection>` on the latest one (chat loop only); harness XML is
  XSD-guarded (`src/main/resources/agent/*.xsd`) and stripped before every
  store — stored chats never carry it.
- **Locks**: per-chat PostgreSQL session-level advisory locks
  (`db/AdvisoryChatLockManager.kt`) serialize runs (409 on contention) and
  deletes. REQUIREMENT on `database.url` pooling: see the
  `AdvisoryChatLockManager.kt` KDoc.
- **History mutation by message index** (no message ids): truncate
  (`DELETE /api/chats/{id}/messages/{index}` — no extraction) and fork
  (`POST /api/chats/{id}/fork/{index}`); both validate via
  `ChatCodec.validateChat`, ops in `agent/chat/ChatService.kt`.
- **Tools** (`agent/tool/`): `ToolProvider.kt` is the SPI;
  `CombinedToolProvider` merges namespaced children (`{namespace}__{tool}` —
  the join/split contract is `agent/tool/Namespaces.kt`; an empty namespace
  set gives one-shot bare names);
  `WhitelistedToolProvider` applies the persona/investigator whitelists;
  `LengthSafeToolProvider` caps successful tool-result text;
  `mcp/McpToolProvider.kt` fronts MCP servers (streamable-HTTP + stdio;
  per-server `toolExecutionTimeoutSeconds`; the per-round tool-list
  refresh is the sole reconnection point); `agent/tool/filesystem/
  FsToolProvider.kt` is the read-only filesystem mock. Execution runs
  brain-side through the hand's callback (`POST /api/hand/tool` →
  `hand/HandCallbackService.kt`) — a round's tool calls execute IN
  PARALLEL, results reassemble in call order (authoritative:
  `hand-pi/src/run.ts`).
- **Compaction & memory extraction** (`agent/pipeline/compaction/`,
  `agent/pipeline/eltm/MemoryExtractionService.kt`): proactive
  token-fraction trigger + reactive `context_exhausted` compaction, wired
  in `PersistChatService.kt`. EVERY path that drops history (chat deletion
  AND compaction) feeds the background extraction queue
  (`memory/eltm/ExtractionQueue.kt` + `ExtractionQueueWorker.kt`,
  SQS-style visibility lease) — extraction never runs on the request path,
  and a failed extraction never fails a chat run. Extracted facts go
  straight to the ELTM writer (`agent/pipeline/eltm/EltmWriterService.kt`
  + `memory/eltm/EltmToolProvider.kt`), which deduplicates re-runs. The
  manual write path is `POST /api/eltm/import` →
  `MemoryExtractionService.processUserImport`
  (`server/endpoint/EltmRoute.kt`; request/response shape in the README
  API table).
- **Pipeline models**: `memory.compactModel`, `memory.eltm.*`,
  `agent.investigator.model`, `title.model` are ALL REQUIRED and resolved
  once at boot — never a chat run's model; constraints are validated in
  `config/MemoryConfig.kt` / `config/AgentConfig.kt` and documented by
  `config.schema.json`.
- **Sub-pipelines**: the investigator
  (`agent/pipeline/investigate/InvestigatorService.kt` — its OWN tool set
  = MCP + read-only `eltm`; `round_limit`/`context_exhausted` stops are
  recovered elastically), the query rewrite
  (`agent/pipeline/rewrite/QueryRewriteService.kt` — feeds the
  `<memories>` injection), the title generator
  (`agent/pipeline/TitleGenerator.kt` — `POST /api/chats/{id}/title`, no
  lock). Shared one-shot plumbing: `agent/pipeline/OneShotRuns.kt`.
- **frontend/**: Svelte 5 + Vite + TS, Tailwind v4 (tokens in
  `src/app.css`), bits-ui; the dev server proxies `/api` to ktor. Hash
  routing (`src/lib/routes.ts` + `src/lib/router.svelte.ts`);
  module-scope stores (`src/lib/chat-store.svelte.ts`,
  `src/lib/ui-store.svelte.ts`, `src/lib/toast-store.svelte.ts`,
  `src/lib/persona-store.svelte.ts`); the run loop
  (`src/lib/stream-session.ts`); the wire client (`src/lib/api.ts`);
  ELTM browse + import
  (`src/lib/components/EltmView.svelte`, `src/lib/paged-tab.svelte.ts`,
  `src/lib/paging.ts`); personas (`src/lib/components/PersonaView.svelte`,
  `src/lib/components/PersonaDropdown.svelte`); chat UI
  (`src/lib/components/ChatView.svelte`, `Composer.svelte`,
  `MessageList.svelte` + `MessageItem.svelte`, `Sidebar.svelte`,
  `ModelDropdown.svelte`, the rename/delete/truncate dialogs,
  `MarkdownContent.svelte`, `ImageLightbox.svelte`,
  `CollapsibleBlock.svelte`); markdown
  (`src/lib/markdown-renderer.ts`); attachments
  (`src/lib/image-attachment.ts` — byte cap + downscale ladder,
  `src/lib/import-form.ts`); pure display/decision helpers
  (`src/lib/display.ts`, `src/lib/chat-logic.ts`); background-resync
  cadence (`src/lib/resync.ts`), focus trap (`src/lib/focus-trap.ts`),
  misc helpers (`src/lib/utils.ts`); the wire-format mirror
  (`src/lib/types.ts`); shared UI primitives in
  `src/lib/components/ui/`. Logic lives in unit-tested modules (colocated
  `*.test.ts`); components and stores are thin reactive hosts.

## Verification commands

```bash
./gradlew test
cd hand-pi && npm test && npm run build && npm run typecheck && npm run lint
cd frontend && npm run check && npm run build && npm test
```

This is the authoritative command list — README's Verification section
points here.

Run them after any relevant source change. They must exit clean. The
DB-backed JVM tests run the production Postgres stores over a throwaway
testcontainers PostgreSQL (`testutil/TestDi.kt`, `testutil/TestDb.kt`); the
network seams (hand client, MCP) are faked. Docker must be available:
without it the tests FAIL FAST with a clear error, never silently skip.

## Code quality and style rules

These sections describe the rules/items to watch out for when writing or
reviewing code. When writing or reviewing code, look for bugs from the
following perspectives:

- Bug detection and correctness: Logic errors, off-by-one mistakes, race conditions, unhandled edge cases, incorrect assumptions, regressions.
- Test coverage and test quality: Coverage gaps, weak assertions, tautological tests, missing scenarios. Are key code paths tested? Do tests actually validate correct behavior? Are unit tests well-structured with meaningful assertions?
  JUnit Jupiter silently DROPS a `@Test` method whose Kotlin return type is
  non-`Unit`: an expression-bodied `= runBlocking { ... }` test whose last
  statement returns a value (e.g. `assertFailsWith`, `assertIs`, `assertNotNull`)
  compiles to a non-void method and is never discovered — no error, no report
  entry. Keep the last statement a `Unit` assertion (bind the value and assert
  on it), and treat a class whose executed-test count is lower than its
  `@Test` count as a bug.
- Performance and security: Inefficiencies, resource leaks, injection risks, insecure defaults, exposed secrets, missing input validation.
- Code quality and style: follow existing pattern (project conventions), no dark magic, no hacky solution/workaround, no complex logic without comments. Maintainability is the first priority.
  - Single Source of Truth for Comments: Only explain logic at its original/primary location. Other places referencing this logic MUST use pointers (e.g., `see [Component/File]`) and MUST NOT repeat the same explanation.
  - Wire Protocol Documentation: see [Backend style](#backend-style).
- Config models and their schema: `config.schema.json` mirrors the config
  models in `config/Config.kt` (and the checked-in `config.example.jsonc`
  documents the shape). Treat the schema as documentation: when the config
  models change — new fields, renames, defaults, validation rules — update
  `config.schema.json` (and `config.example.jsonc` if the example changes)
  in the same change. Reviews must check the schema and the models match.

## Backend style

- Coroutine-native: DB access goes through `db/Database.kt`'s `withTransaction`
  (an Exposed suspend transaction wrapped in `withContext(Dispatchers.IO)`);
  never call blocking JDBC on the event loop.
- Never log secrets (passwords, API keys, session cookies).
- The hand is stateless and opinionless: it must never add/remove/rewrite
  message content, store anything, or log content/secrets. The wire format
  conventions (message shape, events, callback payloads) are owned by
  `agent/chat/ChatMessage.kt` and enforced by `agent/chat/ChatCodec.kt`
  (the golden fixture `hand-pi/test/fixtures/chat-golden.json` guards
  them) — mirror any Kotlin-side change into `hand-pi/src/types.ts` and
  its tests, one schema across DB, brain, and hand. Other components MUST
  only reference the Kotlin definitions, *unless* a component has
  special/different parsing or unique working behavior that requires
  component-specific explanation. The hand trusts Kotlin
  to send valid messages (Kotlin validates on encode): hand-side
  validation covers the request envelope only.
- Prefer fail-fast design

## Operational rule

- DO NOT touch git after making changes. User should review the change and manually stage the changes.
