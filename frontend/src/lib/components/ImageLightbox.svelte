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

  let overlayEl = $state<HTMLElement | null>(null)
  let imgEl = $state<HTMLImageElement | null>(null)
  let closeBtn = $state<HTMLButtonElement | null>(null)
  let scale = $state(1)
  let tx = $state(0)
  let ty = $state(0)
  let dragging = $state(false)
  let dragStart = $state<{ x: number; y: number; tx: number; ty: number } | null>(null)

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
    const next = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale * Math.pow(1.0015, -e.deltaY)))
    if (next === scale) return
    const localX = (px - cx - tx) / scale
    const localY = (py - cy - ty) / scale
    tx = px - cx - localX * next
    ty = py - cy - localY * next
    scale = next
    clampTranslate()
  }

  function onPointerDown(e: PointerEvent) {
    dragging = true
    dragStart = { x: e.clientX, y: e.clientY, tx, ty }
    imgEl?.setPointerCapture(e.pointerId)
  }

  function onPointerMove(e: PointerEvent) {
    const start = dragStart
    if (!start) return
    tx = start.tx + (e.clientX - start.x)
    ty = start.ty + (e.clientY - start.y)
    clampTranslate()
  }

  function endDrag() {
    dragging = false
    dragStart = null
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
  onclick={(e) => {
    if (e.target === e.currentTarget) onClose()
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
    onpointerdown={onPointerDown}
    onpointermove={onPointerMove}
    onpointerup={endDrag}
    onpointercancel={endDrag}
    onlostpointercapture={endDrag}
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
