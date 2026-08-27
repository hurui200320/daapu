<script lang="ts">
  import { CheckCircle2, GitFork, Lightbulb, Trash2, Wrench, XCircle } from '@lucide/svelte'
  import { chatStore as store } from '../chat-store.svelte'
  import { partOrdinalKey, roundSignature } from '../display'
  import type { ChatAttachmentPart, ChatMessage, ChatMessagePart, ChatToolResultPart, TextPart } from '../types'
  import CollapsibleBlock from './CollapsibleBlock.svelte'
  import ImageLightbox from './ImageLightbox.svelte'
  import MarkdownContent from './MarkdownContent.svelte'
  import { lightboxTriggerBtn, toolPreBlock } from './ui/message-styles'

  let {
    message,
    index,
    onTruncate,
  }: {
    message: ChatMessage
    index: number
    onTruncate: (index: number) => void
  } = $props()

  // per-part open overrides for this message's collapsibles: the user's own
  // toggle wins over the closed default — the override records it so a
  // re-render (e.g. a streamed delta elsewhere in the list) cannot force the
  // block back open. The overrides live in the store, keyed by the message's
  // round signature ([roundSignature]) and by part type + ordinal within the
  // type (tool results key on their result id instead — see [partOrdinalKey];
  // not the raw part index): the stored form's part layout can differ
  // from the display commit's coalesced one, and the toggles must keep
  // pointing at the same collapsible across the done-reload — and at the
  // same ROUND, wherever a mid-run compaction relocates it to (a different
  // round at the same position never inherits the toggles). Overrides are
  // inert once the message no longer has the part: the blocks stay open only
  // while the user keeps them open.
  const signature = $derived(roundSignature(message))
  const partOverrides = $derived(store.partOverridesBySignature[signature] ?? {})

  // the image currently open in the fullscreen viewer, or null while closed;
  // the trigger button is remembered so closing can return focus to it
  let lightboxSrc = $state<string | null>(null)
  let lightboxTrigger: HTMLButtonElement | null = null

  // the viewer must never show an image that no longer belongs to this
  // message: MessageItem is reconciled by index (unkeyed each in
  // MessageList), so a history edit while the viewer is open — a mid-run
  // compaction, a truncate, the done-reload — can re-target this component
  // at a different message. Same-image-at-same-index reloads stay open.
  $effect(() => {
    const src = lightboxSrc
    if (src === null) return
    const stillThere = message.parts.some((p) => {
      if (isImage(p)) return imageSrc(p) === src
      if (p.type === 'tool_result') return p.parts.some((q) => isImage(q) && imageSrc(q) === src)
      return false
    })
    if (!stillThere) lightboxSrc = null
  })

  function openLightbox(e: MouseEvent, src: string) {
    lightboxTrigger = e.currentTarget as HTMLButtonElement | null
    lightboxSrc = src
  }

  // history edits must never race the streaming buffers (the optimistic
  // user message / uncommitted tool rounds are not in the DB, so indices
  // computed on the display list would target the wrong message), and any
  // other pending history edit makes indices stale until it settles — a
  // full-chat delete's extraction, a truncation or a fork all disable
  const actionsDisabled = $derived(store.streaming || store.isMutatingHistory(store.chatId))

  const canFork = $derived(message.role === 'assistant' && message.finishReason === 'stop')

  // hover-revealed action buttons: hidden until the message row is hovered
  // (or focused), and invisible while disabled so dead buttons never show.
  // Absolutely positioned into the mt-8 gap below the message (a user message
  // is always followed by the spacing gap, an assistant stop message too —
  // the stop message after a tool chain is the only non-chained one), so a
  // message never reserves space for a button that is not visible.
  const actionBtn =
    'absolute -bottom-8 right-0 flex size-6 items-center justify-center rounded-md text-muted-foreground/60 opacity-0 transition hover:bg-foreground/10 hover:text-foreground focus-visible:opacity-100 group-hover:opacity-100 no-hover:opacity-100 disabled:pointer-events-none disabled:opacity-0'

  function imageSrc(part: ChatAttachmentPart): string | null {
    const content = part.content
    if (content.type === 'base64' && content.base64) {
      return `data:${part.mimeType};base64,${content.base64}`
    }
    return null
  }

  function isImage(part: ChatMessagePart): part is ChatAttachmentPart {
    return part.type === 'attachment' && part.kind === 'image'
  }

  function argsText(args: Record<string, unknown>): string {
    return JSON.stringify(args, null, 2)
  }

  /**
   * A tool result carries its text as nested text parts; join those instead
   * of dumping the raw JSON. Trimmed: tool outputs commonly start/end with
   * blank lines, which whitespace-pre-wrap would render as big empty gaps.
   */
  function toolResultText(part: ChatToolResultPart): string {
    return part.parts
      .flatMap((p) => (p.type === 'text' ? [p.text] : []))
      .join('')
      .trim()
  }
</script>

{#if message.role === 'user'}
  <div class="group relative flex flex-col items-end gap-2">
    {#each message.parts.filter(isImage) as img (img)}
      {@const src = imageSrc(img)}
      {#if src}
        <button
          type="button"
          title="view fullscreen"
          aria-label="view fullscreen"
          class={lightboxTriggerBtn()}
          onclick={(e) => openLightbox(e, src)}
        >
          <img class="block h-40 max-w-full rounded-lg object-cover" {src} alt="attachment" />
        </button>
      {/if}
    {/each}
    {#each message.parts.filter((p): p is TextPart => p.type === 'text') as part (part)}
      <div
        class="max-w-[80%] whitespace-pre-wrap break-words rounded-[1.125rem] bg-primary/15 px-4 py-2 text-foreground backdrop-blur-md"
        style="overflow-wrap: anywhere"
      >
        {part.text}
      </div>
    {/each}
    <button
      title="delete this message and everything after"
      class={actionBtn}
      disabled={actionsDisabled}
      onclick={() => onTruncate(index)}
    >
      <Trash2 class="size-3.5" />
    </button>
  </div>
{:else}
  <div class="group relative w-full">
    {#each message.parts as part, pi (part)}
      {@const partKey = partOrdinalKey(message.parts, pi)}
      {#if part.type === 'text' && part.text}
        <MarkdownContent text={part.text} />
      {:else if part.type === 'reasoning'}
        <CollapsibleBlock
          icon={Lightbulb}
          title="Reasoning"
          open={partOverrides[partKey] ?? false}
          onOpenChange={(v) => store.setPartOverride(signature, partKey, v)}
        >
          <div class="text-sm text-muted-foreground">
            <MarkdownContent text={part.content} />
          </div>
        </CollapsibleBlock>
      {:else if isImage(part)}
        {@const src = imageSrc(part)}
        {#if src}
          <button
            type="button"
            title="view fullscreen"
            aria-label="view fullscreen"
            class={lightboxTriggerBtn()}
            onclick={(e) => openLightbox(e, src)}
          >
            <img class="block max-h-80 max-w-full rounded-lg" {src} alt="attachment" />
          </button>
        {/if}
      {:else if part.type === 'tool_call'}
        <CollapsibleBlock
          icon={Wrench}
          title={part.tool}
          open={partOverrides[partKey] ?? false}
          onOpenChange={(v) => store.setPartOverride(signature, partKey, v)}
        >
          <pre class={toolPreBlock()}>{argsText(part.args)}</pre>
        </CollapsibleBlock>
      {:else if part.type === 'tool_result'}
        <CollapsibleBlock
          icon={part.isError ? XCircle : CheckCircle2}
          title={part.tool}
          subtitle={part.isError ? '(error)' : ''}
          open={partOverrides[partKey] ?? false}
          onOpenChange={(v) => store.setPartOverride(signature, partKey, v)}
        >
          {#if toolResultText(part)}
            <pre class={toolPreBlock()}>{toolResultText(part)}</pre>
          {/if}
          {#each part.parts.filter(isImage) as img (img)}
            {@const src = imageSrc(img)}
            {#if src}
              <button
                type="button"
                title="view fullscreen"
                aria-label="view fullscreen"
                class={lightboxTriggerBtn('mt-2')}
                onclick={(e) => openLightbox(e, src)}
              >
                <img class="block max-h-80 max-w-full rounded-lg" {src} alt="attachment" />
              </button>
            {/if}
          {/each}
        </CollapsibleBlock>
      {/if}
    {/each}
    {#if message.meta?.modelId || message.meta?.totalTokens != null || message.finishReason}
      <div class="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground tabular-nums">
        {#if message.meta?.modelId}<span>{message.meta.modelId}</span>{/if}
        {#if message.meta?.totalTokens != null}<span>{message.meta.totalTokens.toLocaleString()} tokens</span>{/if}
        {#if message.finishReason}<span>finish: {message.finishReason}</span>{/if}
      </div>
    {/if}
    {#if canFork}
      <button
        title="fork the conversation from here"
        class={actionBtn}
        disabled={actionsDisabled}
        onclick={() => void store.forkChat(index)}
      >
        <GitFork class="size-3.5" />
      </button>
    {/if}
  </div>
{/if}

{#if lightboxSrc}
  <ImageLightbox
    src={lightboxSrc}
    alt="attachment"
    onClose={() => {
      lightboxSrc = null
      // the trigger may have been removed by a history edit while the
      // viewer was open; focusing a detached element is a safe no-op
      lightboxTrigger?.focus()
      lightboxTrigger = null
    }}
  />
{/if}
