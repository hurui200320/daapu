<script lang="ts">
  import { ArrowDown, Lightbulb, Wrench } from '@lucide/svelte'
  import { chatStore as store } from '../chat-store.svelte'
  import type { ChatMessage } from '../types'
  import CollapsibleBlock from './CollapsibleBlock.svelte'
  import MarkdownContent from './MarkdownContent.svelte'
  import MessageItem from './MessageItem.svelte'
  import TruncateMessagesDialog from './TruncateMessagesDialog.svelte'

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

  // the chat + user-message index a pending truncation starts from, or null
  // while closed; the chat id is pinned at open time so a chat switch before
  // the confirm can never redirect the delete onto a different chat
  let truncateTarget = $state<{ chatId: string; index: number } | null>(null)

  let scrollEl = $state<HTMLElement | null>(null)
  let atBottom = $state(true)
  let rafId = 0

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
  // scrolled up. The MutationObserver below covers every reactive DOM change
  // (streamed text, committed rounds, collapsibles) plus non-reactive growth
  // (markdown reflow, image loads via the capture-phase load listener).
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
    // the initial render precedes observe(), so a freshly-mounted chat
    // (content already in the DOM) must pin to the bottom explicitly; the
    // capture-phase load listener below still covers images that load later
    rafId = requestAnimationFrame(() => {
      if (atBottom) el.scrollTop = el.scrollHeight
    })
    // images inside markdown grow the content without mutating the DOM
    const onImageLoad = () => {
      if (atBottom) el.scrollTop = el.scrollHeight
    }
    el.addEventListener('load', onImageLoad, true)
    return () => {
      cancelAnimationFrame(rafId)
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
    <!-- the bottom padding is pb-10 (not py-6): the last message's action
         button hangs -bottom-8 (32px) below its row, so the padding must
         clear that overhang or the button clips at the scroll edge -->
    <div class="mx-auto flex w-full max-w-3xl flex-col px-4 pt-6 pb-10">
      {#each store.messages as message, i}
        <div class={messageSpacing(store.messages, i)}>
          <MessageItem
            {message}
            index={i}
            onTruncate={(idx) => (truncateTarget = { chatId: store.chatId, index: idx })}
          />
        </div>
      {/each}

      {#if store.streaming && !store.runEnding}
        <!-- the live round follows a tool chain without the big gap; the
             final stored form re-applies the spacing rules after reload -->
        <div class="w-full" class:mt-8={store.messages.at(-1)?.role !== 'tool_result'}>
          {#if store.streamReasoning}
            <CollapsibleBlock
              icon={Lightbulb}
              title="Reasoning"
              shimmer
              open={store.streamReasoningOpen}
              onOpenChange={(v) => (store.streamReasoningOpen = v)}
            >
              <div class="text-sm text-muted-foreground">
                <MarkdownContent text={store.streamReasoning} live />
              </div>
            </CollapsibleBlock>
          {/if}
          {#each store.streamToolCalls as call, i}
            <CollapsibleBlock
              icon={Wrench}
              title={call.name}
              shimmer
              open={store.streamToolCallsOpen[i] ?? true}
              onOpenChange={(v) => (store.streamToolCallsOpen[i] = v)}
            >
              <pre
                class="max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-code-background p-3 font-mono text-xs leading-5 text-code-foreground"
              >{JSON.stringify(call.args, null, 2)}</pre>
            </CollapsibleBlock>
          {/each}
          {#if store.streamText}
            <MarkdownContent text={store.streamText} live />
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

<TruncateMessagesDialog target={truncateTarget} onClose={() => (truncateTarget = null)} />
