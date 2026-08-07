<script lang="ts">
  import { renderMarkdown } from './markdown'
  import {
    ATTACH_IMAGE,
    CONTENT_BASE64,
    CONTENT_URL,
    MSG_ASSISTANT,
    MSG_SYSTEM,
    MSG_USER,
    PART_ATTACHMENT,
    PART_REASONING,
    PART_TEXT,
    PART_TOOL_CALL,
    PART_TOOL_RESULT,
    type KoogAttachmentSource,
    type KoogMessage,
    type KoogPart,
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
    messages: KoogMessage[]
    streaming: boolean
    streamReasoning?: string
    streamText?: string
    streamToolCalls?: { name: string; args: string }[]
    streamToolResults?: { id: string; name: string; content: string; isError: boolean }[]
    retrying?: boolean
    streamError?: string | null
  } = $props()

  function imageSrc(source: KoogAttachmentSource): string | null {
    const content = source.content
    if (!content) return null
    if (content.type === CONTENT_BASE64 && content.base64) {
      const mime = source.mimeType ?? `image/${source.format ?? 'png'}`
      return `data:${mime};base64,${content.base64}`
    }
    if (content.type === CONTENT_URL && content.url) return content.url
    return null
  }

  function isImage(part: KoogPart): boolean {
    return part.type === PART_ATTACHMENT && part.source?.type === ATTACH_IMAGE
  }

  function showReasoning(part: KoogPart): string {
    return (part.content ?? []).join('')
  }

  /**
   * koog serializes a tool result as nested parts (usually a single Text);
   * join those instead of dumping the raw JSON (with koog type names) like
   * the streaming path does.
   */
  function toolResultText(part: KoogPart): string {
    const textParts = (part.parts ?? []).filter((p) => p.type === PART_TEXT && p.text)
    if (textParts.length > 0) return textParts.map((p) => p.text).join('')
    return part.output ?? ''
  }
</script>

<div class="message-list">
  {#each messages as message}
    {#if message.type !== MSG_SYSTEM}
      <div class="message {message.type === MSG_USER ? 'user' : message.type === MSG_ASSISTANT ? 'assistant' : 'other'}">
        {#each message.parts as part}
          {#if part.type === PART_TEXT && part.text}
            <div class="text">{@html renderMarkdown(part.text)}</div>
          {:else if part.type === PART_REASONING}
            <details class="reasoning">
              <summary>Reasoning</summary>
              <div class="text reasoning-text">{@html renderMarkdown(showReasoning(part))}</div>
            </details>
          {:else if isImage(part)}
            {@const src = imageSrc(part.source!)}
            {#if src}
              <img class="attachment" src={src} alt="attachment" />
            {/if}
          {:else if part.type === PART_TOOL_CALL}
            <div class="tool-call">
              <div class="tool-name">tool: {part.tool}</div>
              <pre>{part.args}</pre>
            </div>
          {:else if part.type === PART_TOOL_RESULT}
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
