<script lang="ts">
  import type { ModelInfo } from './types'

  let {
    models,
    disabled = false,
    selectedModel = $bindable(''),
    onSend,
  }: {
    models: ModelInfo[]
    disabled?: boolean
    selectedModel?: string
    /** resolves to whether the message was stored (false restores the draft) */
    onSend: (text: string, images: { dataUrl: string }[], model: string) => Promise<boolean>
  } = $props()

  let text = $state('')
  let images = $state<{ dataUrl: string }[]>([])
  let fileInput: HTMLInputElement

  function addFiles(files: FileList | null) {
    if (!files) return
    for (const file of files) {
      if (!file.type.startsWith('image/')) continue
      const reader = new FileReader()
      reader.onload = () => {
        if (typeof reader.result === 'string') {
          images = [...images, { dataUrl: reader.result }]
        }
      }
      reader.readAsDataURL(file)
    }
  }

  function onPaste(e: ClipboardEvent) {
    addFiles(e.clipboardData?.files ?? null)
  }

  function removeImage(index: number) {
    images = images.filter((_, i) => i !== index)
  }

  async function submit() {
    const trimmed = text.trim()
    if ((!trimmed && images.length === 0) || disabled) return
    const draft = { text, images: [...images] }
    text = ''
    images = []
    let stored = false
    try {
      stored = await onSend(trimmed, draft.images, selectedModel)
    } finally {
      // a rejected or failed send means the message wasn't stored: restore
      // the draft instead of silently losing it
      if (!stored) {
        text = draft.text
        images = draft.images
      }
    }
  }

  function onKeydown(e: KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
      e.preventDefault()
      void submit()
    }
  }
</script>

<div class="composer">
  {#if images.length > 0}
    <div class="image-preview">
      {#each images as image, i}
        <div class="thumb">
          <img src={image.dataUrl} alt={`attachment ${i + 1}`} />
          <button class="remove" onclick={() => removeImage(i)} title="remove">×</button>
        </div>
      {/each}
    </div>
  {/if}
  <div class="controls">
    <select bind:value={selectedModel} disabled={disabled} title="model">
      {#each models as model}
        <option value={model.id}>{model.id}{model.vision ? ' (vision)' : ''}</option>
      {/each}
    </select>
    <button class="attach" onclick={() => fileInput.click()} disabled={disabled} title="attach image">+ image</button>
    <input
      bind:this={fileInput}
      type="file"
      accept="image/*"
      multiple
      hidden
      onchange={(e) => addFiles((e.currentTarget as HTMLInputElement).files)}
    />
    <textarea
      bind:value={text}
      placeholder="message (Enter to send, Shift+Enter for newline, paste to attach image)"
      rows="2"
      disabled={disabled}
      onkeydown={onKeydown}
      onpaste={onPaste}
    ></textarea>
    <button class="send" onclick={() => void submit()} disabled={disabled || (!text.trim() && images.length === 0)}>
      {disabled ? '…' : 'send'}
    </button>
  </div>
</div>

<style>
  .composer {
    border-top: 1px solid var(--border);
    padding: 0.6rem;
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  .image-preview {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
  }

  .thumb {
    position: relative;
  }

  .thumb img {
    max-height: 4.5rem;
    border-radius: 0.4rem;
    display: block;
  }

  .remove {
    position: absolute;
    top: -0.4rem;
    right: -0.4rem;
    width: 1.2rem;
    height: 1.2rem;
    border-radius: 50%;
    border: none;
    background: var(--danger-bg);
    color: var(--danger-fg);
    cursor: pointer;
    line-height: 1;
  }

  .controls {
    display: flex;
    gap: 0.5rem;
    align-items: flex-end;
  }

  textarea {
    flex: 1;
    resize: vertical;
    min-height: 2.6rem;
    padding: 0.5rem 0.7rem;
    border: 1px solid var(--border);
    border-radius: 0.6rem;
    background: var(--input-bg);
    color: var(--text);
    font: inherit;
  }

  button,
  select {
    font: inherit;
    border: 1px solid var(--border);
    border-radius: 0.6rem;
    padding: 0.45rem 0.7rem;
    background: var(--input-bg);
    color: var(--text);
    cursor: pointer;
  }

  button:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  select {
    max-width: 22rem;
  }

  .send {
    background: var(--accent);
    border-color: var(--accent);
    color: var(--accent-fg);
  }
</style>
