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
pipeline only (`Main.kt` currently initializes the stack and exits).

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
  column as one JSON array, keyed by `chat_id`.

When adding chat features, manipulate history via koog (its `ChatHistoryProvider`
and features) rather than inserting message rows directly.
