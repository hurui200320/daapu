<script lang="ts">
  import { untrack } from 'svelte'
  import { ArrowUp, Paperclip, X } from '@lucide/svelte'
  import { chatStore as store } from '../chat-store.svelte'
  import { router } from '../router.svelte'
  import { toastStore } from '../toast-store.svelte'
  import { uiStore } from '../ui-store.svelte'
  import ModelDropdown from './ModelDropdown.svelte'
  import PersonaDropdown from './PersonaDropdown.svelte'

  let text = $state('')
  let images = $state<{ dataUrl: string }[]>([])
  let fileInput: HTMLInputElement
  let textarea: HTMLTextAreaElement

  // the chat the composer's current draft belongs to (null = not yet synced)
  let draftChatId: string | null = null

  // per-chat drafts: the composer stays mounted across chat switches, so the
  // draft is swapped out of / into the store — otherwise text typed in one
  // chat would leak into the next. Only the chat id is a dependency: the
  // save/load reads run untracked, or every keystroke would re-run the swap.
  $effect(() => {
    const id = store.chatId
    untrack(() => {
      if (draftChatId === id) return
      // a deleted chat's draft was dropped with it (chatStore.deleteChat):
      // the switch-away save must not resurrect it
      if (draftChatId !== null && !store.deletedChatIds.has(draftChatId)) {
        store.drafts[draftChatId] = { text, images }
      }
      const draft = store.drafts[id]
      text = draft?.text ?? ''
      images = draft?.images ?? []
      draftChatId = id
    })
  })

  // the active chat is being deleted (the backend extracts memories first,
  // which can take minutes): no message may be sent to it until it is gone
  const deleting = $derived(store.deletingIds.has(store.chatId))

  const usage = $derived(store.usage)
  const usagePct = $derived(
    usage.used != null && usage.context != null && usage.context > 0
      ? Math.min(100, Math.round((usage.used / usage.context) * 100))
      : null,
  )

  // auto-resize (llama's two-liner, run on every keystroke and on draft restore).
  // The chat view stays mounted but CSS-hidden on the ELTM/personas routes, so
  // the initial run can measure a display:none textarea (scrollHeight 0 → height
  // 0px, clipping the placeholder); depending on the route re-runs the
  // measurement once the view is visible again.
  $effect(() => {
    void text
    void router.current
    if (!textarea) return
    textarea.style.height = 'auto'
    textarea.style.height = `${textarea.scrollHeight}px`
  })

  /** Per-attachment byte budget (pre-base64): a 50 MB paste would balloon
   * into reactive state as a base64 string and blow up the request body. */
  const MAX_IMAGE_BYTES = 8 * 1024 * 1024
  /** Longest edge kept when downscaling; vision models gain nothing beyond this. */
  const MAX_IMAGE_EDGE = 1568

  function readAsDataUrl(blob: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(reader.error ?? new Error('read failed'))
      reader.readAsDataURL(blob)
    })
  }

  function canvasToBlob(canvas: HTMLCanvasElement, mime: string, quality?: number): Promise<Blob | null> {
    return new Promise((resolve) => canvas.toBlob(resolve, mime, quality))
  }

  function toastTooLarge(name: string) {
    toastStore.push(`"${name}" is too large (max ${Math.round(MAX_IMAGE_BYTES / 1024 / 1024)} MB)`, 'error')
  }

  /**
   * Encode the downscaled bitmap within [MAX_IMAGE_BYTES]. The primary format
   * keeps PNG alpha; if that still exceeds the budget (noise-heavy sources do,
   * even at [MAX_IMAGE_EDGE]), alpha is flattened onto white BEHIND the pixels
   * (`destination-over` — no black-background JPEG artifact) and lower-quality
   * JPEG steps run until it fits. Returns the data URL, or NULL when the
   * result is still oversized after the full ladder — refused with the
   * oversized toast so nothing balloons into reactive state / request body.
   * Throws only when encoding itself failed.
   */
  async function encodeWithinBudget(
    canvas: HTMLCanvasElement,
    ctx: CanvasRenderingContext2D,
    keepAlpha: boolean,
    name: string,
  ): Promise<string | null> {
    let blob = await canvasToBlob(canvas, keepAlpha ? 'image/png' : 'image/jpeg', 0.85)
    if (blob && blob.size > MAX_IMAGE_BYTES && keepAlpha) {
      ctx.globalCompositeOperation = 'destination-over'
      ctx.fillStyle = '#ffffff'
      ctx.fillRect(0, 0, canvas.width, canvas.height)
      ctx.globalCompositeOperation = 'source-over'
      blob = await canvasToBlob(canvas, 'image/jpeg', 0.85)
    }
    for (const quality of [0.7, 0.55, 0.4]) {
      if (!blob || blob.size <= MAX_IMAGE_BYTES) break
      blob = await canvasToBlob(canvas, 'image/jpeg', quality)
    }
    if (!blob) throw new Error('encode failed')
    if (blob.size <= MAX_IMAGE_BYTES) return await readAsDataUrl(blob)
    toastTooLarge(name)
    return null
  }

  /**
   * One attachment file → data URL. Oversized images are downscaled on a
   * canvas to [MAX_IMAGE_EDGE] and then forced through [encodeWithinBudget]
   * (the budget applies to the OUTPUT too — PNG alpha flattens onto white,
   * JPEG quality steps down, a still-oversized result is refused);
   * everything at or under the edge passes through with its ORIGINAL bytes,
   * so normal screenshots/animated GIFs are untouched.
   */
  async function imageFileToDataUrl(file: File): Promise<string | null> {
    if (!file.type.startsWith('image/')) return null
    if (file.size > MAX_IMAGE_BYTES) {
      toastTooLarge(file.name)
      return null
    }
    try {
      const bitmap = await createImageBitmap(file)
      const longest = Math.max(bitmap.width, bitmap.height)
      if (longest <= MAX_IMAGE_EDGE) {
        bitmap.close()
        return await readAsDataUrl(file)
      }
      const scale = MAX_IMAGE_EDGE / longest
      const canvas = document.createElement('canvas')
      canvas.width = Math.round(bitmap.width * scale)
      canvas.height = Math.round(bitmap.height * scale)
      const ctx = canvas.getContext('2d')
      if (!ctx) throw new Error('canvas unavailable')
      const keepAlpha = file.type === 'image/png'
      if (!keepAlpha) {
        // JPEG has no alpha channel: paint the background first
        ctx.fillStyle = '#ffffff'
        ctx.fillRect(0, 0, canvas.width, canvas.height)
      }
      ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
      bitmap.close()
      return await encodeWithinBudget(canvas, ctx, keepAlpha, file.name)
    } catch {
      // decode/encode failure: unusable in the optimistic bubble AND likely
      // rejected by the backend anyway — reject here with context instead of
      // silently dropping the paste
      toastStore.push(`"${file.name}" could not be processed`, 'error')
      return null
    }
  }

  async function addFiles(files: FileList | null) {
    if (!files) return
    // The picked chat is pinned up front: each decode awaits (createImageBitmap
    // + canvas encodes can take hundreds of ms for several large files), and a
    // chat switch mid-loop would otherwise append results into whatever draft
    // the composer now shows.
    const pickChatId = store.chatId
    for (const file of Array.from(files)) {
      const dataUrl = await imageFileToDataUrl(file)
      if (dataUrl !== null) appendImage(pickChatId, { dataUrl })
    }
    // a stale input value fires no change event for a re-pick of the same
    // file (also after a failed send restores the draft): reset it so the
    // picker always works
    fileInput.value = ''
  }

  /**
   * Route one decoded attachment home: appended to the live composer state
   * while [pickChatId]'s draft is still on screen; parked on that chat's
   * PERSISTED store draft otherwise (the swap effect restores the full list
   * when the user revisits). A chat deleted this session gets nothing —
   * same rule as the draft-swap save.
   */
  function appendImage(pickChatId: string, image: { dataUrl: string }) {
    if (draftChatId === pickChatId) {
      images = [...images, image]
      return
    }
    if (store.deletedChatIds.has(pickChatId)) return
    const draft = store.drafts[pickChatId] ?? { text: '', images: [] }
    draft.images.push(image)
    store.drafts[pickChatId] = draft
  }

  function onPaste(e: ClipboardEvent) {
    const files = e.clipboardData?.files
    if (!files || files.length === 0) return
    // same gate as the paperclip button: no attachments while a run streams
    // or the chat is being deleted; the paste falls through to plain text
    if (store.streaming || deleting) return
    // consume the paste: a clipboard carrying both files and text would
    // otherwise also insert the text into the textarea
    e.preventDefault()
    addFiles(files)
  }

  function removeImage(index: number) {
    images = images.filter((_, i) => i !== index)
  }

  function onKeydown(e: KeyboardEvent) {
    // touch devices: the soft keyboard has no Shift, so Enter must insert a
    // newline (mobile messaging convention) and the send button is the only
    // send path — otherwise multi-line messages are impossible and every
    // return-key tap fires an accidental send
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && !uiStore.coarse) {
      e.preventDefault()
      void submit()
    }
  }

  async function submit() {
    const trimmed = text.trim()
    // no send while the history is still loading (an optimistic message sent
    // before it arrives would be clobbered by the load), nor without a model
    // (the server requires one — an empty id is a guaranteed 400)
    if ((!trimmed && images.length === 0) || store.streaming || deleting || store.chatLoading || !store.selectedModel) {
      return
    }
    const chatId = store.chatId
    const draft = { text, images: [...images] }
    text = ''
    images = []
    let stored = false
    try {
      stored = await store.send(trimmed, draft.images, store.selectedModel)
    } finally {
      // a rejected or failed send means the message wasn't stored: restore
      // the draft instead of silently losing it. The composer stays editable
      // during the run, so prepend the restored draft to anything typed
      // meanwhile instead of clobbering it; if the pending route switched
      // the chat as the run ended, restore into that chat's draft slot
      if (!stored) {
        if (store.chatId === chatId) {
          text = text ? draft.text + '\n' + text : draft.text
          images = draft.images
        } else {
          store.drafts[chatId] = draft
        }
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
        {#each images as image, i (image)}
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
      enterkeyhint={uiStore.coarse ? 'enter' : 'send'}
      class="max-h-[var(--max-message-height)] w-full resize-none overflow-y-auto border-0 bg-transparent px-5 pb-2 pt-3.5 text-base leading-6 outline-none placeholder:text-muted-foreground"
      onkeydown={onKeydown}
      onpaste={onPaste}></textarea>
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
        <PersonaDropdown />
        {#if usage.used != null && usage.context != null}
          <span
            class="hidden truncate whitespace-nowrap text-xs text-muted-foreground tabular-nums sm:inline {usagePct !=
              null && usagePct >= 80
              ? 'text-destructive'
              : ''}"
            title="context usage of the selected model"
          >
            {usage.used.toLocaleString()} / {usage.context.toLocaleString()} tokens · {usagePct}%
          </span>
        {/if}
      </div>
      <button
        type="button"
        onclick={() => void submit()}
        disabled={store.streaming ||
          deleting ||
          store.chatLoading ||
          !store.selectedModel ||
          (!text.trim() && images.length === 0)}
        title="send"
        class="flex size-9 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-40"
      >
        <ArrowUp class="size-4" />
      </button>
    </div>
  </form>
</div>
