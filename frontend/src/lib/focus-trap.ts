/**
 * Keep Tab inside a dialog-like container: the wrap-around half of a focus
 * trap (extracted from ImageLightbox, the app's first fullscreen surface,
 * so the next one reuses it instead of copying the selector list).
 * `fallback` is focused when the container holds nothing focusable (e.g.
 * its only control was removed by a concurrent edit).
 */
export function trapTab(container: HTMLElement, e: KeyboardEvent, fallback?: HTMLElement | null): void {
  if (e.key !== 'Tab') return
  const focusables = Array.from(
    container.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input, textarea, select, [tabindex]:not([tabindex="-1"])',
    ),
  )
  if (focusables.length === 0) {
    e.preventDefault()
    fallback?.focus()
    return
  }
  const first = focusables[0]
  const last = focusables[focusables.length - 1]
  if (e.shiftKey && document.activeElement === first) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault()
    first.focus()
  }
}
