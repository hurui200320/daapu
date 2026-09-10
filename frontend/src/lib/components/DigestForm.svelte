<script lang="ts">
  import { ChevronDown, ChevronUp, GripVertical, Info, Paperclip, Type, X } from '@lucide/svelte'
  import { digestEltm } from '../api'
  import { toastStore } from '../toast-store.svelte'
  import { hasDigestInput, moveToSlot, newTextPart, wireDigestParts, type DigestDraftPart } from '../digest-form'
  import { browserEncoder, imageFileToDataUrl, MAX_IMAGE_BYTES } from '../image-attachment'
  import { cn, errMsg } from '../utils'
  import ImageLightbox from './ImageLightbox.svelte'
  import { lightboxTriggerBtn } from './ui/message-styles'
  import Button from './ui/button.svelte'

  // The parent keeps this component MOUNTED even when another tab is
  // active (CSS-hidden — see EltmView.svelte): the digest request blocks
  // for minutes, so the draft survives switching tabs — and chats (the
  // parent view itself stays mounted, CSS-hidden, on other routes) —
  // mid-write. This component must never be {#if}-mounted, or the draft
  // would be lost.
  let { onsubmitted }: { onsubmitted: () => Promise<void> } = $props()

  // ---- Digest tab (the manual write path): caller-supplied text/image
  // parts run through the memory extraction one-shot and then the ELTM
  // writer agent. The request blocks for both stages (minutes are normal),
  // so the form is its own state machine — `digesting` disables everything
  // and the busy label explains the wait. The draft is an ordered part
  // list (text blocks + images, see digest-form.ts for the pure draft
  // logic), so an email or a document can be digested with its
  // interleaving intact.
  let parts = $state<DigestDraftPart[]>([newTextPart()])
  let digestDate = $state('')
  let digesting = $state(false)
  let digestError = $state<string | null>(null)
  let digestSuccess = $state(false)
  // the hidden file input's ref ($state so the bind:this assignment never
  // trips the non-reactive-update warning — see MessageList.svelte's
  // scrollEl for the same pattern)
  let fileInput = $state<HTMLInputElement | null>(null)

  // the extractor's skip sentinel (MemoryExtractionService.kt owns the
  // canonical value and the tolerant match, isNothingToRemember): the
  // server no-ops a text matching this sentence without any LLM call
  const NOTHING_TO_REMEMBER = 'Nothing worth remember.'

  // whether anything meaningful (a non-blank text block or an image) is
  // drafted — gates the submit button, mirroring the server's 400
  let hasInput = $derived(hasDigestInput(parts))

  // the placeholder example text rides the FIRST text block only
  let firstTextIndex = $derived(parts.findIndex((p) => p.kind === 'text'))

  // the image currently open in the fullscreen viewer, plus the trigger
  // button to restore focus to on close (the MessageItem pattern)
  let lightboxSrc = $state<string | null>(null)
  let lightboxTrigger: HTMLButtonElement | null = null

  function openLightbox(e: MouseEvent, src: string) {
    lightboxTrigger = e.currentTarget as HTMLButtonElement | null
    lightboxSrc = src
  }

  function closeLightbox() {
    lightboxSrc = null
    lightboxTrigger?.focus()
    lightboxTrigger = null
  }

  // the reference date picker's ceiling: the browser's local today, rendered
  // the way the backend parses it (YYYY-MM-DD, see EltmRoute.kt). Page-load
  // time only — the server's future-date 400 stays the authority.
  const TODAY = (() => {
    const d = new Date()
    const mm = String(d.getMonth() + 1).padStart(2, '0')
    const dd = String(d.getDate()).padStart(2, '0')
    return `${d.getFullYear()}-${mm}-${dd}`
  })()

  // two snippets of casual first-person notes — the shape the first text
  // block encourages (any prose works; see the notice above it)
  const TEXT_PLACEHOLDER =
    'I went to Paris the week of May 15, 2026 for a work conference. It was amazing!\n' +
    'Also, I switched from editor A to editor B yesterday because of plugin compatibility.'

  async function submitDigest() {
    if (digesting) return
    const wireParts = wireDigestParts(parts)
    if (wireParts.length === 0) return
    digesting = true
    digestError = null
    digestSuccess = false
    try {
      // no date = the server's today as the reference date (it only
      // anchors the extraction — the writer always stamps the digest day)
      await digestEltm(wireParts, digestDate || undefined)
      digestSuccess = true
      // the server no longer reports whether anything was recorded (the
      // response is a bare 201): a no-op (a pasted sentinel or an empty
      // extraction) is an indistinguishable success, so the draft always
      // clears. The optional reference date deliberately stays — a
      // same-day follow-up digest is the common case.
      // The browse lists pick up the new records via the parent's
      // onsubmitted (the background resync path: unlike a from-scratch
      // load it keeps the loaded pages and the expanded cards'
      // drill-downs, and leaves the lists untouched when a fetch fails).
      parts = [newTextPart()]
      await onsubmitted()
    } catch (e) {
      digestError = errMsg(e)
    } finally {
      digesting = false
    }
  }

  // ---- Part blocks: add/remove/reorder. Images ride the composer's
  // pipeline (see image-attachment.ts and Composer.svelte): per-file
  // budget + downscale ladder. No per-chat draft here — the form is the
  // single always-mounted digest tab.

  /** Per-attachment byte budget (lives with the pipeline in image-attachment.ts). */
  const toastTooLarge = (name: string) =>
    toastStore.push(`"${name}" is too large (max ${Math.round(MAX_IMAGE_BYTES / 1024 / 1024)} MB)`, 'error')

  function addTextPart() {
    parts = [...parts, newTextPart()]
  }

  function removePart(index: number) {
    parts = parts.filter((_, i) => i !== index)
  }

  // ---- Drag to reorder (native HTML5 DnD — mouse only): a block's
  // wrapper is `draggable` only while its handle button is pressed
  // (armed), so text selection inside the textareas is never hijacked.
  // The arm is cleared by any completed press (the window-level pointerup
  // in the markup), covering a release outside the block or the window.
  // Touch devices and keyboard users reorder with the toolbar buttons
  // instead (HTML5 DnD does not work on touch; the buttons reveal on
  // focus-within for keyboard users); the buttons share [reorder] with
  // the drop handler.

  /** The block being dragged, the highlighted drop target, and the block whose handle is armed. */
  let dragIndex = $state<number | null>(null)
  let dropIndex = $state<number | null>(null)
  let draggableIndex = $state<number | null>(null)

  function startDrag(e: DragEvent, index: number) {
    if (digesting) {
      e.preventDefault()
      return
    }
    dragIndex = index
    dropIndex = null
    // Firefox refuses to start the drag without payload data
    e.dataTransfer?.setData('text/plain', String(index))
    if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
  }

  function onDragOver(e: DragEvent, index: number) {
    if (dragIndex === null || dragIndex === index) return
    e.preventDefault()
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
    dropIndex = index
  }

  function onDragLeave(e: DragEvent, index: number) {
    if (dragIndex === null) return
    // moving across the block's children fires dragleave on the wrapper
    // too: only clear the highlight when the pointer left the block
    const wrapper = e.currentTarget as HTMLElement
    if (e.relatedTarget && wrapper.contains(e.relatedTarget as Node)) return
    if (dropIndex === index) dropIndex = null
  }

  function onDrop(e: DragEvent, index: number) {
    e.preventDefault()
    const from = dragIndex
    endDrag()
    if (from === null || from === index) return
    // the drop lands in the pointer's half of the target: top half inserts
    // before it, bottom half after it (a drop carrying no block — e.g. a
    // native text drag out of a textarea — is swallowed by the
    // preventDefault above)
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
    const after = e.clientY > rect.top + rect.height / 2
    reorder(from, after ? index + 1 : index)
  }

  function endDrag() {
    dragIndex = null
    dropIndex = null
    draggableIndex = null
  }

  /**
   * Reorder via the shared slot mover (see [moveToSlot] in digest-form.ts
   * for the slot coordinates and the no-op rule). The touch-only move
   * buttons share it: up = slot i-1, down = slot i+2; a no-op returns the
   * same array, so reactivity only fires on real moves.
   */
  function reorder(from: number, to: number) {
    const next = moveToSlot(parts, from, to)
    if (next !== parts) parts = next
  }

  /**
   * Convert picked/pasted image files into draft image parts, inserted at
   * `insertAt` (the end when omitted — the paperclip appends; a paste
   * inside a text block inserts right after that block).
   */
  async function addFiles(files: FileList | null, insertAt?: number) {
    if (!files) return
    const added: DigestDraftPart[] = []
    for (const file of Array.from(files)) {
      const result = await imageFileToDataUrl(file, browserEncoder)
      if (!result.ok) {
        if (result.reason === 'too-large') toastTooLarge(file.name)
        else if (result.reason === 'unprocessable') {
          toastStore.push(`"${file.name}" could not be processed`, 'error')
        }
        // 'not-an-image': silently skipped, like the paperclip's image/* filter
        continue
      }
      added.push({ kind: 'image', dataUrl: result.dataUrl })
    }
    if (added.length > 0) {
      const at = insertAt ?? parts.length
      parts = [...parts.slice(0, at), ...added, ...parts.slice(at)]
    }
    // a stale input value fires no change event for a re-pick of the same
    // file: reset it so the picker always works
    if (fileInput) fileInput.value = ''
  }

  /**
   * Paste image files while focused in a text block: they insert right
   * after that block. Consumes the paste (a clipboard carrying both files
   * and text would otherwise also insert the text into the textarea) and
   * stops the propagation, so the form-level paste handler does not ALSO
   * append at the end.
   */
  function pasteInTextBlock(e: ClipboardEvent, index: number) {
    // frozen while digesting like every other control: a mid-run paste
    // would mutate a draft the success reset then silently wipes
    if (digesting) {
      e.preventDefault()
      return
    }
    const files = e.clipboardData?.files
    if (!files || files.length === 0) return
    e.preventDefault()
    e.stopPropagation()
    void addFiles(files, index + 1)
  }

  /** Paste image files anywhere else on the form: they append at the end (frozen while digesting — see pasteInTextBlock). */
  function pasteOnForm(e: ClipboardEvent) {
    if (digesting) {
      e.preventDefault()
      return
    }
    const files = e.clipboardData?.files
    if (!files || files.length === 0) return
    e.preventDefault()
    void addFiles(files)
  }
</script>

<!-- any completed press anywhere clears the drag arm (see the reorder
     comment in the script): it must never outlive its own mousedown, even
     when the pointer was released outside the block or the window -->
<svelte:window onpointerup={() => (draggableIndex = null)} />

{#if digestError}
  <div class="break-words rounded-lg border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
    {digestError}
  </div>
{/if}

{#if digestSuccess}
  <div class="rounded-lg border border-border/30 bg-muted/40 px-3 py-2 text-sm text-muted-foreground">
    Digest finished — new records, if any, are on the Entities and Relationships tabs.
  </div>
{/if}

<!-- The notice: the endpoint runs the text and image parts
     (in the order the user arranges them) through the memory
     extraction one-shot and then the ELTM writer agent (see
     EltmRoute.kt), so any prose works — the notice pins what the
     extraction stage does with it. -->
<div class="rounded-2xl border border-border/30 bg-muted/60 p-4 shadow-sm backdrop-blur-md">
  <div class="flex items-start gap-2">
    <Info class="mt-0.5 size-4 shrink-0 text-muted-foreground" />
    <div class="min-w-0 text-sm text-muted-foreground">
      <p>
        Digest runs the text and image blocks below, in the order you arrange them, through the memory extractor, then
        the ELTM writer agent records the extracted facts into the store. You can paste general text, raw notes, or
        summarized facts, and interleave images — an email or a document can go in as alternating text and images:
      </p>
      <ul class="mt-2 list-disc space-y-1 pl-5">
        <li>First-person pronouns ("I", "my") are automatically mapped to "the user"</li>
        <li>Entities, relationships, and diary notes are extracted and recorded automatically</li>
        <li>Provide clear context and absolute dates ("the week of May 15, 2026") when possible</li>
        <li>
          Make the first part a dedicated text block holding
          &lt;user-provided-context&gt;...&lt;/user-provided-context&gt; for your own free-form explanation of what the
          input is (e.g. an email, a PDF, a contract) — the extractor uses it to interpret the rest
        </li>
        <li>Relative dates ("yesterday") resolve against the reference date below, or today when none is set</li>
        <li>
          The reference date only anchors the extraction — the recorded notes are always dated the day of the digest
        </li>
        <li>
          Images are read by the extraction model — the server's extraction model must support vision, or the digest
          fails with a clear error
        </li>
        <li>A text that is just "{NOTHING_TO_REMEMBER}" (with no images) is treated as empty (no-op)</li>
      </ul>
    </div>
  </div>
</div>

<!-- the part form: ordered text/image part blocks plus the toolbar;
     pasting image files inside a text block inserts them right after
     it, anywhere else appends at the end -->
<div class="flex flex-col gap-1" onpaste={pasteOnForm}>
  <div class="flex flex-col gap-1" role="list">
    {#each parts as part, i (part)}
      <!-- the drag handlers are pointer-gesture glue without a
           wrapper-level ARIA pattern (touch devices and keyboard users
           reorder via the toolbar buttons instead) -->
      <div
        role="listitem"
        class={cn(
          'rounded-xl',
          dragIndex !== null && dropIndex === i && 'ring-2 ring-ring/60',
          dragIndex === i && 'opacity-50',
        )}
        draggable={draggableIndex === i && !digesting}
        ondragstart={(e) => startDrag(e, i)}
        ondragover={(e) => onDragOver(e, i)}
        ondragleave={(e) => onDragLeave(e, i)}
        ondrop={(e) => onDrop(e, i)}
        ondragend={endDrag}
      >
        {#if part.kind === 'text'}
          <textarea
            class="min-h-24 w-full resize-y whitespace-pre-wrap break-words rounded-xl border border-border/30 bg-background/60 p-3 text-sm leading-6 outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:opacity-50"
            aria-label="Text block to digest"
            placeholder={firstTextIndex === i ? TEXT_PLACEHOLDER : ''}
            bind:value={part.text}
            onpaste={(e) => pasteInTextBlock(e, i)}
            disabled={digesting}></textarea>
        {:else}
          <!-- @container caps the image at a 1:1 ratio with the block's
               width (max-h = 100cqw): a long mobile screenshot cannot
               blow up the height, while wide images keep their natural
               contained height. Click opens the fullscreen viewer. -->
          <div class="@container">
            <button
              type="button"
              class={lightboxTriggerBtn(
                'block w-full rounded-xl border border-border/30 bg-background/60 p-2 disabled:pointer-events-none disabled:opacity-50',
              )}
              onclick={(e) => openLightbox(e, part.dataUrl)}
              disabled={digesting}
              title="view image"
            >
              <img
                src={part.dataUrl}
                alt={`attachment ${i + 1}`}
                class="max-h-[100cqw] w-full object-contain"
                draggable="false"
              />
            </button>
          </div>
        {/if}
        <div class="group flex items-center justify-end gap-1 pb-1">
          <button
            type="button"
            onmousedown={() => (draggableIndex = i)}
            disabled={digesting}
            title="drag to reorder"
            class="inline-flex size-7 cursor-grab items-center justify-center rounded-md text-muted-foreground transition hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-40 no-hover:hidden"
          >
            <GripVertical class="size-4" />
          </button>
          <button
            type="button"
            onclick={() => reorder(i, i - 1)}
            disabled={digesting || i === 0}
            title="move up"
            class="hidden size-7 no-hover:flex group-focus-within:flex items-center justify-center rounded-md text-muted-foreground transition hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-40"
          >
            <ChevronUp class="size-4" />
          </button>
          <button
            type="button"
            onclick={() => reorder(i, i + 2)}
            disabled={digesting || i === parts.length - 1}
            title="move down"
            class="hidden size-7 no-hover:flex group-focus-within:flex items-center justify-center rounded-md text-muted-foreground transition hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-40"
          >
            <ChevronDown class="size-4" />
          </button>
          <button
            type="button"
            onclick={() => removePart(i)}
            disabled={digesting}
            title="remove block"
            class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground transition hover:bg-destructive/10 hover:text-destructive focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-40"
          >
            <X class="size-4" />
          </button>
        </div>
      </div>
    {/each}
  </div>

  <div class="flex flex-wrap items-center justify-between gap-3 pt-2">
    <div class="flex flex-wrap items-center gap-3">
      <button
        type="button"
        onclick={addTextPart}
        disabled={digesting}
        title="add a text block"
        class="inline-flex h-7 shrink-0 items-center gap-1.5 rounded-md px-2 text-xs text-muted-foreground transition hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-40"
      >
        <Type class="size-4" /> text
      </button>
      <button
        type="button"
        onclick={() => fileInput?.click()}
        disabled={digesting}
        title="add image (or paste)"
        class="inline-flex h-7 shrink-0 items-center gap-1.5 rounded-md px-2 text-xs text-muted-foreground transition hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-40"
      >
        <Paperclip class="size-4" /> image
      </button>
      <input
        bind:this={fileInput}
        type="file"
        accept="image/*"
        multiple
        hidden
        onchange={(e) => addFiles((e.currentTarget as HTMLInputElement).files)}
      />
      <label class="flex items-center gap-2 text-xs text-muted-foreground">
        Reference date (optional)
        <input
          type="date"
          class="rounded-md border border-border/30 bg-background/60 px-2 py-1 text-sm disabled:opacity-50"
          max={TODAY}
          bind:value={digestDate}
          disabled={digesting}
        />
      </label>
    </div>
    <Button size="sm" disabled={digesting || !hasInput} onclick={() => void submitDigest()}>
      {digesting ? 'Writing to the ELTM… this can take a while' : 'Digest'}
    </Button>
  </div>
</div>

{#if lightboxSrc}
  <ImageLightbox src={lightboxSrc} alt="digest image" onClose={closeLightbox} />
{/if}
