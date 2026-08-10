<script lang="ts">
  import { renderMarkdown } from './markdown'
  import type {
    ChatAttachmentPart,
    ChatMessage,
    ChatMessagePart,
    ReasoningPart,
    ChatToolResultPart,
  } from './types'

  let {
    messages,
    streaming,
    streamReasoning = '',
    streamText = '',
    streamToolCalls = [],
    streamToolResults = [],
    retrying = false,
    streamError = null,
  }: {
    messages: ChatMessage[]
    streaming: boolean
    streamReasoning?: string
    streamText?: string
    streamToolCalls?: { name: string; args: string }[]
    streamToolResults?: { id: string; name: string; content: string; isError: boolean }[]
    retrying?: boolean
    streamError?: string | null
  } = $props()

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

  function showReasoning(part: ReasoningPart): string {
    return part.content.join('')
  }

  /**
   * A tool result carries its text as nested text parts; join those instead
   * of dumping the raw JSON.
   */
  function toolResultText(part: ChatToolResultPart): string {
    return part.parts.flatMap((p) => (p.type === 'text' ? [p.text] : [])).join('')
  }
</script>

<div class="message-list">
  {#each messages as message}
    {#if message.role !== 'system'}
      <div class="message {message.role === 'user' ? 'user' : message.role === 'assistant' ? 'assistant' : 'other'}">
        {#each message.parts as part}
          {#if part.type === 'text' && part.text}
            <div class="text">{@html renderMarkdown(part.text)}</div>
          {:else if part.type === 'reasoning'}
            <details class="reasoning">
              <summary>Reasoning</summary>
              <div class="text reasoning-text">{@html renderMarkdown(showReasoning(part))}</div>
            </details>
          {:else if isImage(part)}
            {@const src = imageSrc(part)}
            {#if src}
              <img class="attachment" src={src} alt="attachment" />
            {/if}
          {:else if part.type === 'tool_call'}
            <div class="tool-call">
              <div class="tool-name">tool: {part.tool}</div>
              <pre>{part.args}</pre>
            </div>
          {:else if part.type === 'tool_result'}
            <div class="tool-result">
              <div class="tool-name">tool result: {part.tool}</div>
              <pre>{toolResultText(part)}</pre>
            </div>
          {/if}
        {/each}
      </div>
    {/if}
  {/each}

  {#if streaming}
    <div class="message assistant streaming">
      {#if streamReasoning}
        <details class="reasoning" open>
          <summary>Reasoning</summary>
          <div class="text reasoning-text">{@html renderMarkdown(streamReasoning)}</div>
        </details>
      {/if}
      {#each streamToolCalls as call}
        <div class="tool-call">
          <div class="tool-name">tool: {call.name}</div>
          <pre>{call.args}</pre>
        </div>
      {/each}
      {#each streamToolResults as result}
        <div class="tool-result">
          <div class="tool-name">tool result: {result.name}{result.isError ? ' (error)' : ''}</div>
          <pre>{result.content}</pre>
        </div>
      {/each}
      {#if streamText}
        <div class="text">{@html renderMarkdown(streamText)}</div>
      {/if}
      {#if retrying}<div class="retrying">stream hiccup, retrying…</div>{/if}
      <span class="cursor"></span>
    </div>
  {/if}

  {#if streamError}
    <div class="error-banner">run failed: {streamError}</div>
  {/if}
</div>

<style>
  .message-list {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
    padding: 1rem;
    overflow-y: auto;
    flex: 1;
    min-height: 0;
  }

  .message {
    max-width: 85%;
    padding: 0.6rem 0.9rem;
    border-radius: 0.9rem;
    line-height: 1.5;
  }

  .message.user {
    align-self: flex-end;
    background: var(--bubble-user);
  }

  .message.assistant {
    align-self: flex-start;
    background: var(--bubble-assistant);
  }

  .message.other {
    align-self: flex-start;
    background: var(--bubble-tool);
  }

  .text :global(p) {
    margin: 0.3rem 0;
  }

  .text :global(pre) {
    overflow-x: auto;
    padding: 0.5rem;
    background: var(--code-bg);
    border-radius: 0.4rem;
  }

  .reasoning {
    border-left: 3px solid var(--accent-muted);
    padding-left: 0.5rem;
    color: var(--text-muted);
    margin-bottom: 0.4rem;
  }

  .reasoning summary {
    cursor: pointer;
    font-size: 0.85rem;
    user-select: none;
  }

  .reasoning-text :global(*) {
    color: var(--text-muted);
  }

  .attachment {
    max-width: 100%;
    max-height: 20rem;
    border-radius: 0.5rem;
    display: block;
    margin: 0.3rem 0;
  }

  .tool-call,
  .tool-result {
    background: var(--code-bg);
    border-radius: 0.5rem;
    padding: 0.4rem 0.6rem;
    margin: 0.3rem 0;
    font-size: 0.85rem;
  }

  .tool-call pre,
  .tool-result pre {
    margin: 0.2rem 0 0;
    white-space: pre-wrap;
    word-break: break-word;
  }

  .tool-name {
    color: var(--text-muted);
  }

  .retrying {
    color: var(--text-muted);
    font-style: italic;
    font-size: 0.85rem;
  }

  .error-banner {
    align-self: center;
    background: var(--danger-bg);
    color: var(--danger-fg);
    border: 1px solid var(--danger-border);
    padding: 0.5rem 0.9rem;
    border-radius: 0.6rem;
    max-width: 90%;
    white-space: pre-wrap;
    word-break: break-word;
  }

  .cursor {
    display: inline-block;
    width: 0.6rem;
    height: 1.1rem;
    vertical-align: text-bottom;
    background: var(--accent);
    animation: blink 1s steps(2) infinite;
  }

  @keyframes blink {
    50% {
      opacity: 0;
    }
  }
</style>
