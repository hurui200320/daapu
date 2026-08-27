/**
 * Pure hash→route mapping, split from `router.svelte.ts` so it stays
 * unit-testable without touching `window` (the module-scope Router singleton
 * below reads `window.location.hash` at construction time).
 *
 * Hash-based client routing: routes are `#/chat`, `#/chat/<id>`, `#/eltm`,
 * `#/personas`. The hash is never sent to the server, so the app works from
 * any static host without SPA fallback config; the same reason llama.cpp's
 * webui uses `router: hash`.
 */
export type Route = { name: 'chat'; chatId: string | null } | { name: 'eltm' } | { name: 'personas' }

export const CHAT_HOME: Route = { name: 'chat', chatId: null }

function safeDecode(s: string): string {
  try {
    return decodeURIComponent(s)
  } catch {
    return s
  }
}

/** Pure hash→route mapping ('#/chat/xyz' → chat route), exported for tests. */
export function parseHash(hash: string): Route {
  const path = hash.replace(/^#/, '')
  const chat = /^\/chat(?:\/([^/]+))?\/?$/.exec(path)
  if (chat) return { name: 'chat', chatId: chat[1] ? safeDecode(chat[1]) : null }
  if (path === '/eltm' || path === '/eltm/') return { name: 'eltm' }
  if (path === '/personas' || path === '/personas/') return { name: 'personas' }
  // unknown or empty hash: chat home (the URL bar is left as-is)
  return CHAT_HOME
}
