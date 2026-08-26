/**
 * Mobile chrome state (below the `md` breakpoint, 768px): the sidebar stops
 * being an inline column and becomes an overlay drawer (App.svelte renders
 * the scrim + the hamburger top bar, Sidebar.svelte the drawer itself), and
 * two media queries CSS breakpoints alone can't reach into Svelte logic:
 * `isMobile` picks the sidebar's rail/full branch, `coarse` switches the
 * composer's Enter behavior (soft keyboards have no Shift — Enter must
 * insert a newline on touch devices, matching mobile messaging convention).
 */
class UiStore {
  /** true while the mobile navigation drawer is open (inert on ≥ md). */
  mobileNavOpen = $state(false)
  /** `(max-width: 767.98px)` — Tailwind's `max-md:*` range. */
  isMobile = $state(false)
  /** `(pointer: coarse)` — touch-primary devices (phones, tablets). */
  coarse = $state(false)

  private started = false

  /** Register the matchMedia listeners (once); called from App.svelte onMount. */
  init() {
    if (this.started) return
    this.started = true
    const mobile = window.matchMedia('(max-width: 767.98px)')
    this.isMobile = mobile.matches
    mobile.addEventListener('change', () => {
      this.isMobile = mobile.matches
      // growing past the breakpoint restores the inline sidebar: a drawer
      // left open would render stale state on top of it
      if (!mobile.matches) this.mobileNavOpen = false
    })
    const coarse = window.matchMedia('(pointer: coarse)')
    this.coarse = coarse.matches
    coarse.addEventListener('change', () => (this.coarse = coarse.matches))
  }
}

export const uiStore = new UiStore()
