<script lang="ts">
  import { untrack } from 'svelte'
  import { cn } from '../utils'
  import { renderMarkdown } from '../markdown'
  import '../markdown.css'

  type Props = { text: string; live?: boolean; class?: string; [key: string]: unknown }

  let { text, live = false, class: className = '', ...rest }: Props = $props()

  // Live mode (the streaming buffers): the pipeline below re-processes the
  // WHOLE text per render (marked + highlight.js + KaTeX + DOMPurify), so
  // re-rendering per streamed token is O(n^2) over a stream. Throttle to one
  // render per interval, with a trailing render so the tail of a chunk burst
  // still shows; the full final text always renders via the committed/stored
  // message's non-live MarkdownContent once the round commits.
  const LIVE_RENDER_INTERVAL_MS = 100
  // the initial value is a deliberate one-time snapshot (the effect below
  // owns all later updates) — untrack keeps that explicit
  let rendered = $state(untrack(() => text))
  let lastRender = 0

  $effect(() => {
    const latest = text
    if (!live) {
      rendered = latest
      return
    }
    const elapsed = Date.now() - lastRender
    if (elapsed >= LIVE_RENDER_INTERVAL_MS) {
      lastRender = Date.now()
      rendered = latest
      return
    }
    const timer = setTimeout(() => {
      lastRender = Date.now()
      rendered = latest
    }, LIVE_RENDER_INTERVAL_MS - elapsed)
    return () => clearTimeout(timer)
  })

  const html = $derived(renderMarkdown(rendered))

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
