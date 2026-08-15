<script lang="ts">
  import { CheckCircle2, GitFork, Lightbulb, Trash2, Wrench, XCircle } from '@lucide/svelte'
  import { chatStore as store } from '../chat-store.svelte'
  import type { ChatAttachmentPart, ChatMessage, ChatMessagePart, ChatToolResultPart } from '../types'
  import CollapsibleBlock from './CollapsibleBlock.svelte'
  import MarkdownContent from './MarkdownContent.svelte'

  let {
    message,
    index,
    onTruncate,
  }: { message: ChatMessage; index: number; onTruncate: (index: number) => void } = $props()

  // history edits must never race the streaming buffers (the optimistic
  // user message / uncommitted tool rounds are not in the DB, so indices
  // computed on the display list would target the wrong message), and the
  // backend refuses them while a full-chat delete's extraction runs; a fork
  // in flight on this chat is the only other pending history edit, so it
  // disables too
  const actionsDisabled = $derived(
    store.streaming || store.deletingIds.has(store.chatId) || store.forkingIds.has(store.chatId)
  )

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
    {#each message.parts.filter(isImage) as img}
      {@const src = imageSrc(img)}
      {#if src}
        <img class="h-40 max-w-full rounded-lg object-cover" src={src} alt="attachment" />
      {/if}
    {/each}
    {#each message.parts.filter((p) => p.type === 'text') as part}
      <div
        class="max-w-[80%] whitespace-pre-wrap break-words rounded-[1.125rem] bg-primary/15 px-4 py-2 text-foreground backdrop-blur-md"
        style="overflow-wrap: anywhere"
      >{(part as { type: 'text'; text: string }).text}</div>
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
    {#each message.parts as part}
      {#if part.type === 'text' && part.text}
        <MarkdownContent text={part.text} />
      {:else if part.type === 'reasoning'}
        <CollapsibleBlock icon={Lightbulb} title="Reasoning">
          <div class="text-sm text-muted-foreground">
            <MarkdownContent text={part.content} />
          </div>
        </CollapsibleBlock>
      {:else if isImage(part)}
        {@const src = imageSrc(part)}
        {#if src}
          <img class="max-h-80 max-w-full rounded-lg" src={src} alt="attachment" />
        {/if}
      {:else if part.type === 'tool_call'}
        <CollapsibleBlock icon={Wrench} title={part.tool}>
          <pre
            class="max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-code-background p-3 font-mono text-xs leading-5 text-code-foreground"
          >{argsText(part.args)}</pre>
        </CollapsibleBlock>
      {:else if part.type === 'tool_result'}
        <CollapsibleBlock
          icon={part.isError ? XCircle : CheckCircle2}
          title={part.tool}
          subtitle={part.isError ? '(error)' : ''}
        >
          {#if toolResultText(part)}
            <pre
              class="max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-code-background p-3 font-mono text-xs leading-5 text-code-foreground"
            >{toolResultText(part)}</pre>
          {/if}
          {#each part.parts.filter(isImage) as img}
            {@const src = imageSrc(img)}
            {#if src}
              <img class="mt-2 max-h-80 max-w-full rounded-lg" src={src} alt="attachment" />
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
