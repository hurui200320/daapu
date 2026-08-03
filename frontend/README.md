# Daapu frontend

Web UI for Daapu, a Kotlin/Ktor chat server. React 19 + TypeScript (strict) + Vite.

## Scripts

```bash
npm run dev        # Vite dev server; proxies /api/* to the backend on :8080
npm run typecheck  # tsc -b
npm run lint       # oxlint
npm test           # vitest
npm run build      # typecheck + build into ../src/main/resources/static for Ktor
```

## Layout

- `src/api/` — typed `fetch` wrapper (`client.ts`) and shared API types (`types.ts`).
  JSON endpoints go through `request()`; SSE streaming goes through
  `api.sendMessage`, which parses `data: ...` frames until `[DONE]`.
- `src/pages/` — `LoginPage` (auth) and `ChatPage` (chat list + streaming composer).
- `src/App.tsx` / `src/router.ts` — session restore via `/api/auth/me` and route gating.
- `src/test/` — vitest + Testing Library tests.
