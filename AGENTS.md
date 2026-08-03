# AGENTS.md

Guidance for AI agents (and humans) working in this project.

## Project

Web-based LLM ChatBot with a memory system.

The project is a Kotlin/Ktor backend serving a React frontend (Vite, in
`frontend/`), backed by PostgreSQL with the pgvector extension. Multi-user
accounts per instance; chats are first-class (ChatGPT-style); the memory
system is a later phase.

## Verification commands

Backend:

```bash
./gradlew test
```

Frontend (from `frontend/`):

```bash
npm run typecheck
npm run lint
npm test
```

Run all of them after any relevant source change. They must exit clean.

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
- All API routes live under `/api/*`; static frontend assets are served from
  `src/main/resources/static/`.
- Plain HTTP only — TLS is terminated by an external reverse proxy.
- Never log secrets (passwords, API keys, session cookies).

## Frontend style

- TypeScript strict mode is on. Do not use `any`.
- API access goes through `frontend/src/api/`; streaming reads the SSE
  response body via `fetch` + `ReadableStream` (see `client.ts`).
- Dev server proxies `/api/*` to the backend on port 8080; do not hardcode URLs.
