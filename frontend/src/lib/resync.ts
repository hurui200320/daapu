/**
 * Shared background-refresh cadence (chat list, persona catalog, ELTM view):
 * a periodic tick plus a window-focus refresh. Returns a disposer for the
 * view-owned users (the app-lifetime stores ignore it).
 */
export function onIntervalAndFocus(ms: number, fn: () => void): () => void {
  const interval = setInterval(fn, ms)
  window.addEventListener('focus', fn)
  return () => {
    clearInterval(interval)
    window.removeEventListener('focus', fn)
  }
}
