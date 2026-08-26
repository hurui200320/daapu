<script lang="ts">
  import { cn } from '../utils'
  import { renderMarkdown } from '../markdown'
  import '../markdown.css'

  type Props = { text: string; class?: string; [key: string]: unknown }

  let { text, class: className = '', ...rest }: Props = $props()

  const html = $derived(renderMarkdown(text))

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
