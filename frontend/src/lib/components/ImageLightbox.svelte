<script lang="ts">
  import { X } from '@lucide/svelte'

  let {
    src,
    alt = 'attachment',
    onClose,
  }: {
    src: string
    alt?: string
    onClose: () => void
  } = $props()

  // zoom floor = the fitted size (zooming out is capped there), ceiling 2000%
  const MIN_SCALE = 1
  const MAX_SCALE = 20
  // a background tap (click-to-close) with more than this much pointer travel
  // is a drag/pinch that ended over the background — the overlay's click
  // handler must not close the viewer then
  const TAP_MOVE_TOLERANCE = 6

  let overlayEl = $state<HTMLElement | null>(null)
  let imgEl = $state<HTMLImageElement | null>(null)
  let closeBtn = $state<HTMLButtonElement | null>(null)
  let scale = $state(1)
  let tx = $state(0)
  let ty = $state(0)
  let dragging = $state(false)
  // single-pointer pan state (imperative: nothing renders from it)
  let dragStart: { x: number; y: number; tx: number; ty: number } | null = null
  // two-pointer pinch state: the gesture's baseline distance + the overlay-
  // relative midpoint to anchor the zoom on (imperative, same)
  let pinchStart: {
    dist: number
    mx: number
    my: number
    scale: number
    tx: number
    ty: number
  } | null = null
  // live pointer positions plus their gesture-start positions (the latter
  // feed the tap-movement tolerance)
  const pointers = new Map<number, { x: number; y: number; startX: number; startY: number }>()
  // how far the farthest pointer has traveled since its press: a background
  // tap must not close the viewer after a drag or pinch that ended over the
  // background (its click lands on the overlay — see the click handler)
  let gestureMaxMove = 0

  // focus the close button on mount so keyboard input lands in the dialog
  // (the app's scroll containers are inner `overflow-y-auto` elements — the
  // body never scrolls, so no page-scroll lock is needed; `touch-none` on
  // the overlay blocks touch scrolling, and the non-passive wheel listener
  // below blocks wheel scrolling of the list behind)
  $effect(() => {
    closeBtn?.focus()
  })

  // Svelte attaches `onwheel` attributes as passive listeners, so the
  // preventDefault() inside them would be ignored and the wheel event would
  // scroll the chat list behind the full-screen overlay. Attach the zoom
  // handler as a non-passive listener instead.
  $effect(() => {
    const el = overlayEl
    if (!el) return
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  })

  /**
   * Keep Tab inside the dialog: the close button is its only focusable
   * element, so Tab wraps back onto it instead of reaching the page behind.
   */
  function onKeydown(e: KeyboardEvent) {
    if (e.key !== 'Tab') return
    const overlay = overlayEl
    if (!overlay) return
    const focusables = Array.from(
      overlay.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input, textarea, select, [tabindex]:not([tabindex="-1"])'
      )
    )
    if (focusables.length === 0) {
      e.preventDefault()
      closeBtn?.focus()
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

  function clampTranslate() {
    const overlay = overlayEl
    const img = imgEl
    if (!overlay || !img) return
    const rect = overlay.getBoundingClientRect()
    const maxTx = Math.max(0, (img.clientWidth * scale - rect.width) / 2)
    const maxTy = Math.max(0, (img.clientHeight * scale - rect.height) / 2)
    tx = Math.min(maxTx, Math.max(-maxTx, tx))
    ty = Math.min(maxTy, Math.max(-maxTy, ty))
  }

  /**
   * Cursor-anchored wheel zoom: the image point under the pointer stays
   * stationary while the scale changes. Pure geometry around the image's
   * transform-origin center, so it works before the image has loaded too.
   */
  function onWheel(e: WheelEvent) {
    e.preventDefault()
    const overlay = overlayEl
    if (!overlay) return
    const rect = overlay.getBoundingClientRect()
    const px = e.clientX - rect.left
    const py = e.clientY - rect.top
    const cx = rect.width / 2
    const cy = rect.height / 2
    // normalize to pixels: some configurations (e.g. Firefox on Linux)
    // report wheel deltas in lines, which would otherwise crawl ~16x slower
    const dy = e.deltaMode === 1 ? e.deltaY * 16 : e.deltaY
    const next = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale * Math.pow(1.0015, -dy)))
    if (next === scale) return
    const localX = (px - cx - tx) / scale
    const localY = (py - cy - ty) / scale
    tx = px - cx - localX * next
    ty = py - cy - localY * next
    scale = next
    clampTranslate()
  }

  /**
   * Pointer state machine, on the OVERLAY (every press lands in it — a pinch
   * finger that goes down on the background is tracked too, so a pinch can
   * start with a finger outside the image): one pointer pans (drag), two
   * pinch-zoom around the gesture midpoint. No pointer capture: the overlay
   * is the whole dialog surface, so a finger leaving the image still
   * delivers its move/up events here, and a click after a completed gesture
   * targets the element the finger actually lifted over (image or close
   * button — never a spurious background close). `touch-none` on the
   * overlay/img suppresses the browser's default touch actions (including
   * native pinch-zoom), so the zoom must be implemented here or touch users
   * cannot zoom at all.
   *
   * One gap of a capture-less design: a press that drags out of the window
   * and releases there never delivers its pointerup — the `pointerleave`
   * handler below ends the pointer at the window edge (the overlay covers
   * the whole viewport, so that is its only way out), keeping the map free
   * of ghost pointers that would corrupt the next gesture's pinch detection.
   */
  function onPointerDown(e: PointerEvent) {
    const first = pointers.size === 0
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY, startX: e.clientX, startY: e.clientY })
    if (first) gestureMaxMove = 0
    dragging = true
    if (pointers.size === 2) {
      startPinch()
      dragStart = null
    } else if (pointers.size === 1) {
      dragStart = { x: e.clientX, y: e.clientY, tx, ty }
    }
  }

  function startPinch() {
    const [a, b] = [...pointers.values()]
    // the midpoint in overlay-center-relative coordinates (like the wheel
    // zoom's pointer): the image's transform-origin is its own center,
    // which sits at the overlay center — without the center offset the
    // anchored point would drift by half the overlay size per zoom step.
    // The overlay is bound before any pointer event can arrive, so the rect
    // is always available; the fallback (raw client midpoint) only guards
    // the unreachable null case.
    const rect = overlayEl?.getBoundingClientRect()
    const mx = rect ? (a.x + b.x) / 2 - rect.left - rect.width / 2 : (a.x + b.x) / 2
    const my = rect ? (a.y + b.y) / 2 - rect.top - rect.height / 2 : (a.y + b.y) / 2
    pinchStart = {
      dist: Math.hypot(a.x - b.x, a.y - b.y),
      mx,
      my,
      scale,
      tx,
      ty,
    }
  }

  function onPointerMove(e: PointerEvent) {
    const p = pointers.get(e.pointerId)
    if (!p) return
    p.x = e.clientX
    p.y = e.clientY
    gestureMaxMove = Math.max(
      gestureMaxMove,
      Math.hypot(e.clientX - p.startX, e.clientY - p.startY)
    )
    if (pinchStart && pointers.size >= 2) {
      const [a, b] = [...pointers.values()]
      const dist = Math.hypot(a.x - b.x, a.y - b.y)
      // a zero distance (both fingers on the same pixel) has no ratio to
      // zoom from yet; a zero BASELINE means the pinch started coincident —
      // the first separation becomes the baseline, else the ratio below
      // would be Infinity and the pinch deadlocked until a finger lifted
      if (dist === 0) return
      if (pinchStart.dist === 0) startPinch()
      // same cursor-anchored geometry as the wheel zoom, anchored at the
      // gesture midpoint instead of the pointer
      const next = Math.min(MAX_SCALE, Math.max(MIN_SCALE, pinchStart.scale * (dist / pinchStart.dist)))
      const localX = (pinchStart.mx - pinchStart.tx) / pinchStart.scale
      const localY = (pinchStart.my - pinchStart.ty) / pinchStart.scale
      tx = pinchStart.mx - localX * next
      ty = pinchStart.my - localY * next
      scale = next
      clampTranslate()
    } else if (dragStart) {
      tx = dragStart.tx + (e.clientX - dragStart.x)
      ty = dragStart.ty + (e.clientY - dragStart.y)
      clampTranslate()
    }
  }

  function endPointer(e: PointerEvent) {
    pointers.delete(e.pointerId)
    if (pointers.size === 0) {
      dragging = false
      dragStart = null
      pinchStart = null
    } else if (pinchStart && pointers.size === 1) {
      // one finger lifted: the pinch becomes a pan anchored at the current
      // transform
      pinchStart = null
      const [p] = [...pointers.values()]
      dragStart = { x: p.x, y: p.y, tx, ty }
    } else if (pointers.size === 2) {
      // an extra finger lifted: re-baseline the pinch on the two remaining
      startPinch()
    }
  }
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && onClose()} />

<div
  bind:this={overlayEl}
  role="dialog"
  aria-modal="true"
  aria-label="image viewer"
  tabindex="-1"
  class="fixed inset-0 z-[60] flex cursor-zoom-out touch-none items-center justify-center bg-black/85"
  onpointerdown={onPointerDown}
  onpointermove={onPointerMove}
  onpointerup={endPointer}
  onpointercancel={endPointer}
  onpointerleave={(e) => {
    // the pointer left the window mid-press: its pointerup will never arrive
    // (no capture), so end the gesture now — an untracked leave (hover, or a
    // touch/pen that already got pointercancel) is a no-op
    if (pointers.has(e.pointerId)) endPointer(e)
  }}
  onclick={(e) => {
    // click-to-close on the background only, and only for a real tap: a
    // drag/pinch that ended over the background (its click lands here — the
    // common ancestor of the image press and the background release) must
    // not close the viewer
    if (e.target === e.currentTarget && gestureMaxMove <= TAP_MOVE_TOLERANCE) onClose()
  }}
  onkeydown={onKeydown}
>
  <img
    bind:this={imgEl}
    src={src}
    alt={alt}
    draggable={false}
    class="max-h-[92vh] max-w-[92vw] touch-none select-none object-contain {dragging ? 'cursor-grabbing' : 'cursor-grab'}"
    style="transform: translate({tx}px, {ty}px) scale({scale})"
  />
  <button
    type="button"
    bind:this={closeBtn}
    title="close (Esc)"
    onclick={onClose}
    class="absolute right-4 top-4 rounded-md p-2 text-foreground/80 transition-colors hover:bg-foreground/10 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
  >
    <X class="size-5" />
    <span class="sr-only">Close</span>
  </button>
  <div class="pointer-events-none absolute bottom-4 right-4 rounded-md bg-black/50 px-2 py-1 text-xs tabular-nums text-white/80">
    {Math.round(scale * 100)}%
  </div>
</div>
