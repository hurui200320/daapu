<script lang="ts">
  import { CheckCircle2, Lightbulb, Wrench, XCircle } from '@lucide/svelte'
  import type { ChatAttachmentPart, ChatMessage, ChatMessagePart, ChatToolResultPart } from '../types'
  import CollapsibleBlock from './CollapsibleBlock.svelte'
  import MarkdownContent from './MarkdownContent.svelte'

  let { message }: { message: ChatMessage } = $props()

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
  <div class="flex flex-col items-end gap-2">
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
  </div>
{:else}
  <div class="w-full">
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
  </div>
{/if}
