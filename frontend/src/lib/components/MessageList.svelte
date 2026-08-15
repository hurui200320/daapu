<script lang="ts">
  import { tick } from 'svelte'
  import { ArrowDown, Lightbulb, Wrench } from '@lucide/svelte'
  import { chatStore as store } from '../chat-store.svelte'
  import type { ChatMessage } from '../types'
  import CollapsibleBlock from './CollapsibleBlock.svelte'
  import MarkdownContent from './MarkdownContent.svelte'
  import MessageItem from './MessageItem.svelte'

  /**
   * Vertical rhythm: standalone messages sit 2rem apart, but a tool chain
   * (assistant tool calls → tool_result → next tool round) is visually
   * glued together — only the chain's first message keeps the full gap, the
   * rest are separated by the blocks' own 4px margins.
   */
  function messageSpacing(messages: ChatMessage[], i: number): string {
    if (i === 0) return ''
    const prev = messages[i - 1]
    const curr = messages[i]
    const chained =
      curr.role === 'tool_result' ||
      (prev.role === 'tool_result' && curr.parts.some((p) => p.type === 'tool_call'))
    return chained ? '' : 'mt-8'
  }

  let scrollEl = $state<HTMLElement | null>(null)
  let atBottom = $state(true)
  let rafId = 0

  // the live round's collapsibles start open, but the user may collapse them
  // mid-stream: a literal `open={true}` would re-open them on every streamed
  // delta, so the open state lives here. A fresh round — the buffer going
  // empty->non-empty (new round after a tool commit, or a retry wipe) —
  // re-opens them; within a round the user's toggle wins.
  let reasoningOpen = $state(true)
  let toolCallOpen: boolean[] = $state([])
  let prevReasoning = ''
  let prevToolCalls = 0

  $effect(() => {
    const cur = store.streamReasoning
    if (prevReasoning === '' && cur !== '') reasoningOpen = true
    prevReasoning = cur
  })

  $effect(() => {
    const cur = store.streamToolCalls.length
    if (prevToolCalls === 0 && cur > 0) {
      toolCallOpen = [true]
    } else if (cur > prevToolCalls) {
      toolCallOpen = [...toolCallOpen, true]
    }
    prevToolCalls = cur
  })

  // switching chats always starts pinned to the bottom (fresh-mount
  // behavior), instead of inheriting the previous chat's scroll state
  $effect(() => {
    void store.chatId
    atBottom = true
  })

  // track whether the user is reading history (scrolled up): while pinned to
  // the bottom the stream auto-scrolls, otherwise the user stays put. No
  // initial sync here: on mount the content is pinned to the bottom below,
  // and a freshly-mounted list must not start "scrolled up".
  $effect(() => {
    const el = scrollEl
    if (!el) return
    const onScroll = () => {
      atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => el.removeEventListener('scroll', onScroll)
  })

  // follow the stream: keep pinned to the bottom while the user hasn't
  // scrolled up. State changes (messages/stream buffers) and non-reactive
  // growth (markdown reflow, image loads) both land on the next frame.
  $effect(() => {
    const el = scrollEl
    if (!el || !atBottom) return
    void store.messages
    void store.streamText
    void store.streamReasoning
    void store.streamToolCalls
    void store.streaming
    void store.retrying
    void tick().then(() => {
      if (atBottom) el.scrollTop = el.scrollHeight
    })
  })

  $effect(() => {
    const el = scrollEl
    if (!el) return
    const observer = new MutationObserver(() => {
      if (!atBottom) return
      cancelAnimationFrame(rafId)
      rafId = requestAnimationFrame(() => {
        if (atBottom) el.scrollTop = el.scrollHeight
      })
    })
    observer.observe(el, { childList: true, subtree: true, characterData: true })
    // images inside markdown grow the content without mutating the DOM
    const onImageLoad = () => {
      if (atBottom) el.scrollTop = el.scrollHeight
    }
    el.addEventListener('load', onImageLoad, true)
    return () => {
      observer.disconnect()
      el.removeEventListener('load', onImageLoad, true)
    }
  })

  function scrollToBottom() {
    atBottom = true
    scrollEl?.scrollTo({ top: scrollEl.scrollHeight })
  }
</script>

<div class="relative h-full">
  <div bind:this={scrollEl} class="h-full overflow-y-auto">
    <div class="mx-auto flex w-full max-w-3xl flex-col px-4 py-6">
      {#each store.messages as message, i}
        <div class={messageSpacing(store.messages, i)}>
          <MessageItem {message} />
        </div>
      {/each}

      {#if store.streaming}
        <!-- the live round follows a tool chain without the big gap; the
             final stored form re-applies the spacing rules after reload -->
        <div class="w-full" class:mt-8={store.messages.at(-1)?.role !== 'tool_result'}>
          {#if store.streamReasoning}
            <CollapsibleBlock
              icon={Lightbulb}
              title="Reasoning"
              shimmer
              open={reasoningOpen}
              onOpenChange={(v) => (reasoningOpen = v)}
            >
              <div class="text-sm text-muted-foreground">
                <MarkdownContent text={store.streamReasoning} />
              </div>
            </CollapsibleBlock>
          {/if}
          {#each store.streamToolCalls as call, i}
            <CollapsibleBlock
              icon={Wrench}
              title={call.name}
              shimmer
              open={toolCallOpen[i] ?? true}
              onOpenChange={(v) => (toolCallOpen[i] = v)}
            >
              <pre
                class="max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-code-background p-3 font-mono text-xs leading-5 text-code-foreground"
              >{JSON.stringify(call.args, null, 2)}</pre>
            </CollapsibleBlock>
          {/each}
          {#if store.streamText}
            <MarkdownContent text={store.streamText} />
          {/if}
          {#if store.retrying}
            <div class="text-xs italic text-muted-foreground">stream hiccup, retrying…</div>
          {/if}
          {#if !store.streamReasoning && !store.streamText && store.streamToolCalls.length === 0}
            <div class="shimmer-text text-sm font-medium">Processing…</div>
          {/if}
        </div>
      {/if}
    </div>
  </div>

  {#if !atBottom}
    <button
      onclick={scrollToBottom}
      title="scroll to bottom"
      class="absolute bottom-4 left-1/2 flex h-9 w-9 -translate-x-1/2 items-center justify-center rounded-full bg-accent text-accent-foreground shadow-md transition hover:bg-accent/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
    >
      <ArrowDown class="size-4" />
    </button>
  {/if}
</div>
