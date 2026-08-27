<script lang="ts">
  import { untrack } from 'svelte'
  import { cn } from '../utils'
  import { getMarkdownRenderer } from '../markdown-renderer'
  import '../markdown.css'

  type Props = { text: string; live?: boolean; class?: string; [key: string]: unknown }

  let { text, live = false, class: className = '', ...rest }: Props = $props()

  // Live mode (the streaming buffers): the pipeline below re-processes the
  // WHOLE text per render (marked + highlight.js + KaTeX + DOMPurify), so
  // re-rendering per streamed token is O(n^2) over a stream. Throttle to one
  // render per interval, with a trailing render so the tail of a chunk burst
  // still shows; the full final text always renders via the committed/stored
  // message's non-live MarkdownContent once the round commits. The interval
  // scales with the buffer (see liveRenderInterval below) so the per-second
  // cost stays flat on long responses.
  const LIVE_RENDER_BASE_MS = 100
  const LIVE_RENDER_MAX_MS = 1000
  // +1 ms of spacing per 100 buffered chars, capped at 1 s: each render is
  // O(n) in the buffered text, so spacing renders by the buffer length keeps
  // the cost per second roughly constant; the 100 ms floor keeps short
  // streams snappy. Measured against the PREVIOUS render's length (a plain
  // field, not reactive state — the throttle must not add dependencies).
  let lastLen = 0
  const liveRenderInterval = () => Math.min(LIVE_RENDER_MAX_MS, LIVE_RENDER_BASE_MS + lastLen / 100)
  // the initial value is a deliberate one-time snapshot (the effect below
  // owns all later updates) — untrack keeps that explicit
  let rendered = $state(untrack(() => text))
  let lastRender = 0

  $effect(() => {
    const latest = text
    if (!live) {
      lastLen = latest.length
      rendered = latest
      return
    }
    const interval = liveRenderInterval()
    const elapsed = Date.now() - lastRender
    if (elapsed >= interval) {
      lastRender = Date.now()
      lastLen = latest.length
      rendered = latest
      return
    }
    const timer = setTimeout(() => {
      lastRender = Date.now()
      lastLen = latest.length
      rendered = latest
    }, interval - elapsed)
    return () => clearTimeout(timer)
  })

  // async pipeline application: awaited once per app session (lazy-loaded),
  // then effectively synchronous. The sequence counter guards against an
  // older resolve overwriting a newer render if two effect runs race while
  // the first initialization is still in flight.
  let html = $state('')
  let seq = 0
  $effect(() => {
    const current = rendered
    const mySeq = ++seq
    let alive = true
    getMarkdownRenderer()
      .then((renderMarkdown) => {
        if (!alive || mySeq !== seq) return
        html = renderMarkdown(current)
      })
      .catch(() => {
        // lazy-init failed (a rejected promise, never swallowed by the cache —
        // the next effect run re-attempts init): leave the previous html alone
      })
    return () => {
      alive = false
    }
  })

  /**
   * The copy buttons inside the injected HTML are wired via event delegation
   * (the innerHTML is replaced on every streamed chunk, so per-node listeners
   * would leak). "Copied!" feedback resets after 1.5s; a re-render restores
   * the default label anyway.
   */
  async function onCopyClick(e: MouseEvent) {
    const btn = (e.target as HTMLElement).closest<HTMLButtonElement>('.code-copy-btn')
    if (!btn) return
    const pre = btn.closest('.code-block-wrapper')?.querySelector('pre')
    if (!pre) return
    try {
      await navigator.clipboard.writeText(pre.textContent ?? '')
    } catch {
      // clipboard permission denied; leave the button label untouched
      return
    }
    btn.textContent = 'Copied!'
    setTimeout(() => {
      if (btn.isConnected) btn.textContent = 'Copy'
    }, 1500)
  }
</script>

<div class={cn('md-content', className)} onclick={onCopyClick} {...rest}>
  {@html html}
</div>
