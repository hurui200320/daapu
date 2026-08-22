/**
 * Hash-based client router. Routes: `#/chat` (home), `#/chat/<id>`,
 * `#/eltm`. Hash routing
 * needs no server cooperation — the hash is never sent to the server, so the
 * app works from any static host (vite dev/preview, ktor static, nginx)
 * without SPA fallback config; the same reason llama.cpp's webui uses
 * `router: hash`.
 *
 * The URL is the source of truth for the active view and the open chat:
 * App.svelte translates route changes into chatStore picks/closes, and store
 * actions that change the open chat (create/fork/delete) navigate the hash.
 */
export type Route = { name: 'chat'; chatId: string | null } | { name: 'eltm' }

const CHAT_HOME: Route = { name: 'chat', chatId: null }

function safeDecode(s: string): string {
  try {
    return decodeURIComponent(s)
  } catch {
    return s
  }
}

function parseHash(hash: string): Route {
  const path = hash.replace(/^#/, '')
  const chat = /^\/chat(?:\/([^/]+))?\/?$/.exec(path)
  if (chat) return { name: 'chat', chatId: chat[1] ? safeDecode(chat[1]) : null }
  if (path === '/eltm' || path === '/eltm/') return { name: 'eltm' }
  // unknown or empty hash: chat home (the URL bar is left as-is)
  return CHAT_HOME
}

class Router {
  current = $state<Route>(parseHash(window.location.hash))
  private started = false

  /** Register the hashchange listener (once); called from App.svelte onMount. */
  init() {
    if (this.started) return
    this.started = true
    window.addEventListener('hashchange', () => {
      this.current = parseHash(window.location.hash)
    })
  }
}

export const router = new Router()

/**
 * Navigate to a path ('/chat/xyz'). The route state is updated synchronously
 * so an effect scheduled in the same microtask batch observes the NEW route
 * (the hashchange event only fires later, as a separate task — a store action
 * that changes state AND navigates would otherwise transiently re-pick the
 * stale route's chat). Setting the same hash fires no event; the hashchange
 * listener later re-parses the same value (a fresh, equal route object — a
 * harmless redundant effect run).
 */
export function navigate(path: string) {
  router.current = parseHash(path)
  window.location.hash = '#' + path
}

/**
 * Like navigate, but replaces the current history entry (no back-target for
 * the replaced route, no hashchange event). Used by the delete flow: the
 * deleted chat's route must not survive as a back target — replaceState keeps
 * the pre-delete history intact, so back lands on the chat before it.
 */
export function replaceRoute(path: string) {
  router.current = parseHash(path)
  history.replaceState(null, '', '#' + path)
}

/** Route path for the chat home ('/chat'). */
export function chatHomePath(): string {
  return '/chat'
}

/** Route path for a chat ('/chat/xyz'), encoded. */
export function chatPath(chatId: string): string {
  return '/chat/' + encodeURIComponent(chatId)
}

/** href for a chat route ('#/chat/xyz'), for use in <a href>. */
export function chatHref(chatId: string): string {
  return '#' + chatPath(chatId)
}
