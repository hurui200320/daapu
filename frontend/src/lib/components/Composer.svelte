<script lang="ts">
  import { ArrowUp, Paperclip, X } from '@lucide/svelte'
  import { chatStore as store } from '../chat-store.svelte'
  import ModelDropdown from './ModelDropdown.svelte'

  let text = $state('')
  let images = $state<{ dataUrl: string }[]>([])
  let fileInput: HTMLInputElement
  let textarea: HTMLTextAreaElement

  // the active chat is being deleted (the backend extracts memories first,
  // which can take minutes): no message may be sent to it until it is gone
  const deleting = $derived(store.deletingIds.has(store.chatId))

  const usage = $derived(store.usage)
  const usagePct = $derived(
    usage.used != null && usage.context != null && usage.context > 0
      ? Math.min(100, Math.round((usage.used / usage.context) * 100))
      : null
  )

  // auto-resize (llama's two-liner, run on every keystroke and on draft restore)
  $effect(() => {
    void text
    if (!textarea) return
    textarea.style.height = 'auto'
    textarea.style.height = `${textarea.scrollHeight}px`
  })

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

  function onKeydown(e: KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
      e.preventDefault()
      void submit()
    }
  }

  async function submit() {
    const trimmed = text.trim()
    if ((!trimmed && images.length === 0) || store.streaming || deleting) return
    const draft = { text, images: [...images] }
    text = ''
    images = []
    let stored = false
    try {
      stored = await store.send(trimmed, draft.images, store.selectedModel)
    } finally {
      // a rejected or failed send means the message wasn't stored: restore
      // the draft instead of silently losing it
      if (!stored) {
        text = draft.text
        images = draft.images
      }
    }
  }
</script>

<div class="mx-auto w-full max-w-3xl px-4 pb-4 pt-1">
  <form
    class="rounded-3xl border border-border/30 bg-muted/60 shadow-sm backdrop-blur-md transition-all focus-within:border-border focus-within:shadow-md"
    onsubmit={(e) => e.preventDefault()}
  >
    {#if images.length > 0}
      <div class="flex flex-wrap gap-2 px-4 pt-4">
        {#each images as image, i}
          <div class="relative">
            <img src={image.dataUrl} alt={`attachment ${i + 1}`} class="h-20 rounded-xl object-cover" />
            <button
              type="button"
              onclick={() => removeImage(i)}
              title="remove attachment"
              class="absolute -right-1.5 -top-1.5 flex size-5 items-center justify-center rounded-full bg-destructive text-destructive-foreground shadow-sm transition hover:bg-destructive/90"
            >
              <X class="size-3" />
            </button>
          </div>
        {/each}
      </div>
    {/if}
    <textarea
      bind:this={textarea}
      bind:value={text}
      rows="1"
      placeholder="Message…"
      class="max-h-[var(--max-message-height)] w-full resize-none overflow-y-auto border-0 bg-transparent px-5 pb-2 pt-3.5 text-[15px] leading-6 outline-none placeholder:text-muted-foreground"
      onkeydown={onKeydown}
      onpaste={onPaste}
    ></textarea>
    <div class="flex items-center justify-between gap-2 px-3 pb-2.5">
      <div class="flex min-w-0 items-center gap-1.5">
        <button
          type="button"
          onclick={() => fileInput.click()}
          disabled={store.streaming || deleting}
          title="attach image (or paste)"
          class="inline-flex size-8 shrink-0 items-center justify-center rounded-md text-muted-foreground transition hover:bg-muted-foreground/15 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50"
        >
          <Paperclip class="size-4" />
        </button>
        <input
          bind:this={fileInput}
          type="file"
          accept="image/*"
          multiple
          hidden
          onchange={(e) => addFiles((e.currentTarget as HTMLInputElement).files)}
        />
        <ModelDropdown />
        {#if usage.used != null && usage.context != null}
          <span
            class="hidden truncate whitespace-nowrap text-xs text-muted-foreground tabular-nums sm:inline {usagePct != null && usagePct >= 80 ? 'text-destructive' : ''}"
            title="context usage of the selected model"
          >
            {usage.used.toLocaleString()} / {usage.context.toLocaleString()} tokens · {usagePct}%
          </span>
        {/if}
      </div>
      <button
        type="button"
        onclick={() => void submit()}
        disabled={store.streaming || deleting || (!text.trim() && images.length === 0)}
        title="send"
        class="flex size-9 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-40"
      >
        <ArrowUp class="size-4" />
      </button>
    </div>
  </form>
</div>
