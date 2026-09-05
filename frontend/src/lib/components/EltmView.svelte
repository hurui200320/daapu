<script lang="ts">
  import { untrack } from 'svelte'
  import { ChevronDown, ChevronRight, ChevronUp, GripVertical, Info, Paperclip, Type, X } from '@lucide/svelte'
  import {
    ELTM_DRILLDOWN_LIMIT,
    getEntityNotes,
    getEntityRelationships,
    getRelationshipNotes,
    digestEltm,
    listEntities,
    listRelationships,
  } from '../api'
  import type { EltmNoteDto, EntityViewDto, RelationshipViewDto } from '../types'
  import { onIntervalAndFocus } from '../resync'
  import { PagedTab } from '../paged-tab.svelte'
  import { router } from '../router.svelte'
  import { toastStore } from '../toast-store.svelte'
  import { hasDigestInput, moveToSlot, newTextPart, wireDigestParts, type DigestDraftPart } from '../digest-form'
  import { browserEncoder, imageFileToDataUrl, MAX_IMAGE_BYTES } from '../image-attachment'
  import { cn, errMsg } from '../utils'
  import ImageLightbox from './ImageLightbox.svelte'
  import { lightboxTriggerBtn } from './ui/message-styles'
  import Button from './ui/button.svelte'

  type Tab = 'entities' | 'relationships' | 'digest'

  interface EntityDetails {
    relationships: RelationshipViewDto[]
    notes: EltmNoteDto[]
    // the notes fetch carries a one-row probe past ELTM_DRILLDOWN_LIMIT:
    // true only when it arrived (an exact-window payload is complete)
    truncated: boolean
    error?: string
  }

  interface RelationshipDetails {
    notes: EltmNoteDto[]
    truncated: boolean
    error?: string
  }

  const TABS: [Tab, string][] = [
    ['entities', 'Entities'],
    ['relationships', 'Relationships'],
    ['digest', 'Digest'],
  ]

  const entitiesTab = new PagedTab<EntityViewDto, EntityDetails>(
    (limit, offset) => listEntities(limit, offset),
    async (id) => {
      const [relationships, notes] = await Promise.all([
        getEntityRelationships(id),
        getEntityNotes(id, ELTM_DRILLDOWN_LIMIT + 1),
      ])
      return {
        relationships,
        notes: notes.slice(0, ELTM_DRILLDOWN_LIMIT),
        truncated: notes.length > ELTM_DRILLDOWN_LIMIT,
      }
    },
    () => ({ relationships: [], notes: [], truncated: false }),
  )

  const relationshipsTab = new PagedTab<RelationshipViewDto, RelationshipDetails>(
    (limit, offset) => listRelationships(limit, offset),
    async (id) => {
      const notes = await getRelationshipNotes(id, ELTM_DRILLDOWN_LIMIT + 1)
      return { notes: notes.slice(0, ELTM_DRILLDOWN_LIMIT), truncated: notes.length > ELTM_DRILLDOWN_LIMIT }
    },
    () => ({ notes: [], truncated: false }),
  )

  let tab = $state<Tab>('entities')
  let error = $state<string | null>(null)
  // true until the FIRST list fetch settles: the empty states must not flash
  // "no entities yet" while that first load is in flight
  let initialLoading = $state(true)
  // false until the view's first visit: the first visit loads the browse
  // window, later visits resync (keeping the loaded pages)
  let loadedOnce = false

  async function refresh() {
    // the first visit's from-scratch load (later visits and the digest
    // success path resync instead — see submitDigest): replace both lists
    // and clear any stale error banner
    error = null
    try {
      await Promise.all([entitiesTab.load(), relationshipsTab.load()])
    } catch (e) {
      error = errMsg(e)
    } finally {
      initialLoading = false
    }
  }

  /**
   * Background resync (30s + window focus): the extraction pipeline writes to
   * the ELTM server-side, so the view must refresh on its own. Both lists
   * must succeed before the banner clears (a failed fetch keeps the current
   * lists and any existing banner), and the expanded cards' drill-down
   * payloads refresh with the lists.
   */
  async function resync() {
    const [entitiesOk, relationshipsOk] = await Promise.all([entitiesTab.resync(), relationshipsTab.resync()])
    if (!entitiesOk || !relationshipsOk) return
    // the fetch succeeded: a stale banner (e.g. the first visit's failed
    // load) is resolved
    error = null
    await Promise.all([entitiesTab.refreshExpanded(), relationshipsTab.refreshExpanded()])
  }

  // one "load more" handler for both tabs (PagedTab.loadMore is itself
  // double-click safe): a failed page leaves the error in the banner, a
  // success clears any stale one first
  type AnyTab = PagedTab<EntityViewDto, EntityDetails> | PagedTab<RelationshipViewDto, RelationshipDetails>

  async function loadMore(tab: AnyTab) {
    error = null
    try {
      await tab.loadMore()
    } catch (e) {
      error = errMsg(e)
    }
  }

  // ---- Digest tab (the manual write path): caller-supplied text/image
  // parts run through the memory extraction one-shot and then the ELTM
  // writer agent. The request blocks for both stages (minutes are normal),
  // so the form is its own state machine — `digesting` disables everything
  // and the busy label explains the wait. Lives in the always-mounted
  // view, so the draft survives switching tabs/chats mid-write. The draft
  // is an ordered part list (text blocks + images, see digest-form.ts for
  // the pure draft logic), so an email or a document can be digested with
  // its interleaving intact.
  let parts = $state<DigestDraftPart[]>([newTextPart()])
  let digestDate = $state('')
  let digesting = $state(false)
  let digestError = $state<string | null>(null)
  let digestSuccess = $state(false)
  // the hidden file input: the tab renders conditionally, so the ref is
  // $state (the bind:this assignment re-runs on tab toggles) — see
  // MessageList.svelte's scrollEl for the same pattern
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
      // The browse lists pick up the new records via the background resync
      // path: unlike refresh() it keeps the loaded pages and the expanded
      // cards' drill-downs (a from-scratch load would collapse them back to
      // page 1) and leaves the lists untouched when a fetch fails.
      parts = [newTextPart()]
      await resync()
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

  // Fetch + poll only while the view is visible (it stays mounted, CSS-hidden
  // on the other routes): the first visit loads the browse window, later
  // visits resync — and the 30s/focus cadence runs only while it is on
  // screen, instead of polling an ELTM page the user never opens. The fetch
  // calls read reactive state (the tabs' row counts, the expanded maps)
  // before their first await, so they must run inside `untrack`: an effect
  // may depend on the route alone — a completed fetch or a card
  // expand/collapse must not re-run the effect (a redundant resync + interval
  // restart per interaction).
  $effect(() => {
    if (router.current.name !== 'eltm') return
    let dispose = () => {}
    untrack(() => {
      if (loadedOnce) {
        void resync()
      } else {
        loadedOnce = true
        void refresh()
      }
      dispose = onIntervalAndFocus(30_000, () => void resync())
    })
    return () => dispose()
  })
</script>

{#snippet chevron(expanded: boolean)}
  {#if expanded}
    <ChevronDown class="size-4 shrink-0 text-muted-foreground" />
  {:else}
    <ChevronRight class="size-4 shrink-0 text-muted-foreground" />
  {/if}
{/snippet}

{#snippet latestNoteLine(note: EltmNoteDto)}
  <p class="mt-1 line-clamp-2 pl-6 text-xs text-muted-foreground">
    <span class="tabular-nums">{note.eventDate}</span> — {note.note}
  </p>
{/snippet}

{#snippet notesList(details: { notes: EltmNoteDto[]; truncated: boolean } | undefined, emptyLabel: string)}
  {#if !details || details.notes.length === 0}
    <p class="text-xs text-muted-foreground">{emptyLabel}</p>
  {:else}
    {#each details.notes as note (note.id)}
      <div class="rounded-lg border border-border/20 bg-background/40 px-3 py-2">
        <span class="text-xs text-muted-foreground tabular-nums">{note.eventDate}</span>
        <p class="whitespace-pre-wrap break-words text-sm leading-6">{note.note}</p>
      </div>
    {/each}
    {#if details.truncated}
      <p class="pt-1 text-xs text-muted-foreground">
        showing the first {ELTM_DRILLDOWN_LIMIT} — older notes exist server-side
      </p>
    {/if}
  {/if}
{/snippet}

{#snippet loadMoreButton(tabState: { canLoadMore: boolean; loadingMore: boolean }, action: () => void)}
  {#if tabState.canLoadMore}
    <div class="flex justify-center">
      <Button size="sm" variant="ghost" disabled={tabState.loadingMore} onclick={action}>Load more</Button>
    </div>
  {/if}
{/snippet}

<!-- any completed press anywhere clears the drag arm (see the reorder
     comment in the script): it must never outlive its own mousedown, even
     when the pointer was released outside the block or the window -->
<svelte:window onpointerup={() => (draggableIndex = null)} />

<div class="h-full overflow-y-auto">
  <div class="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-8">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">ELTM</h1>
      <p class="text-sm text-muted-foreground">
        External long-term memory: entities, relationships, and diary notes (browse is read-only — the writer agent
        writes, see Digest)
      </p>
    </div>

    <div class="flex gap-2">
      {#each TABS as [value, label] (value)}
        <Button
          size="sm"
          variant="ghost"
          class={tab === value ? 'bg-accent text-accent-foreground' : ''}
          onclick={() => (tab = value)}
        >
          {label}
        </Button>
      {/each}
    </div>

    {#if error}
      <div
        class="break-words rounded-lg border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
      >
        {error}
      </div>
    {/if}

    {#if tab === 'entities'}
      {#if entitiesTab.rows.length === 0 && !initialLoading && !error}
        <div class="py-10 text-center text-sm text-muted-foreground">no entities yet</div>
      {/if}

      {#each entitiesTab.rows as view (view.entity.id)}
        <div class="rounded-2xl border border-border/30 bg-muted/60 p-4 shadow-sm backdrop-blur-md">
          <button
            class="flex w-full items-center justify-between gap-2 text-left"
            onclick={() => entitiesTab.toggle(view.entity.id)}
          >
            <span class="flex min-w-0 items-center gap-2">
              {@render chevron(entitiesTab.expanded[view.entity.id] ?? false)}
              <span class="truncate text-sm font-medium">{view.entity.canonicalName}</span>
              <span class="shrink-0 rounded-full bg-accent px-2 py-0.5 text-xs text-accent-foreground">
                {view.entity.category}
              </span>
            </span>
            <span class="shrink-0 text-xs text-muted-foreground tabular-nums">
              #{view.entity.id} · {view.noteCount} notes · {view.relationshipCount} rels
            </span>
          </button>
          {#if Object.keys(view.attributes).length > 0}
            <div class="mt-1 flex flex-wrap gap-1 pl-6">
              {#each Object.entries(view.attributes) as [key, value] (key)}
                <span class="rounded-full border border-border/30 bg-background/40 px-2 py-0.5 text-xs">
                  <span class="text-muted-foreground">{key}</span> <span class="font-medium">{value}</span>
                </span>
              {/each}
            </div>
          {/if}
          {#if view.latestNote}
            {@render latestNoteLine(view.latestNote)}
          {/if}
          {#if entitiesTab.expanded[view.entity.id]}
            {#if !entitiesTab.details[view.entity.id]}
              <p class="mt-3 border-t border-border/30 pt-3 text-xs text-muted-foreground">loading…</p>
            {:else if entitiesTab.details[view.entity.id]!.error}
              <p class="mt-3 border-t border-border/30 pt-3 text-xs text-destructive">
                {entitiesTab.details[view.entity.id]!.error}
              </p>
            {:else}
              <div class="mt-3 space-y-3 border-t border-border/30 pt-3">
                <div>
                  <div class="mb-1 text-xs font-medium text-muted-foreground">relationships</div>
                  {#if entitiesTab.details[view.entity.id]?.relationships.length === 0}
                    <p class="text-xs text-muted-foreground">none</p>
                  {:else}
                    {#each entitiesTab.details[view.entity.id]?.relationships ?? [] as rel (rel.relationship.id)}
                      <div class="rounded-lg border border-border/20 bg-background/40 px-3 py-2">
                        <div class="text-sm">
                          {rel.srcName}
                          <span class="italic text-muted-foreground">{rel.relationship.verb}</span>
                          {rel.dstName}
                        </div>
                        <div class="mt-0.5 flex gap-2 text-xs text-muted-foreground tabular-nums">
                          <span>#{rel.relationship.id}</span>
                          {#if !rel.relationship.valid}
                            <span class="text-destructive">ended</span>
                          {/if}
                          <span>{rel.noteCount} notes</span>
                        </div>
                      </div>
                    {/each}
                  {/if}
                </div>
                <div>
                  <div class="mb-1 text-xs font-medium text-muted-foreground">notes</div>
                  {@render notesList(entitiesTab.details[view.entity.id], 'none')}
                </div>
              </div>
            {/if}
          {/if}
        </div>
      {/each}
      {@render loadMoreButton(entitiesTab, () => void loadMore(entitiesTab))}
    {:else if tab === 'relationships'}
      {#if relationshipsTab.rows.length === 0 && !initialLoading && !error}
        <div class="py-10 text-center text-sm text-muted-foreground">no relationships yet</div>
      {/if}

      {#each relationshipsTab.rows as view (view.relationship.id)}
        <div class="rounded-2xl border border-border/30 bg-muted/60 p-4 shadow-sm backdrop-blur-md">
          <button
            class="flex w-full items-center justify-between gap-2 text-left"
            onclick={() => relationshipsTab.toggle(view.relationship.id)}
          >
            <span class="flex min-w-0 items-center gap-2">
              {@render chevron(relationshipsTab.expanded[view.relationship.id] ?? false)}
              <span class="truncate text-sm font-medium">
                {view.srcName}
                <span class="italic text-muted-foreground">{view.relationship.verb}</span>
                {view.dstName}
              </span>
              {#if !view.relationship.valid}
                <span class="shrink-0 rounded-full bg-destructive/15 px-2 py-0.5 text-xs text-destructive">
                  ended
                </span>
              {:else}
                <span class="shrink-0 rounded-full bg-accent px-2 py-0.5 text-xs text-accent-foreground"> active </span>
              {/if}
            </span>
            <span class="shrink-0 text-xs text-muted-foreground tabular-nums">
              #{view.relationship.id} · {view.noteCount} notes
            </span>
          </button>
          {#if view.latestNote}
            {@render latestNoteLine(view.latestNote)}
          {/if}
          {#if relationshipsTab.expanded[view.relationship.id]}
            {#if !relationshipsTab.details[view.relationship.id]}
              <p class="mt-3 border-t border-border/30 pt-3 text-xs text-muted-foreground">loading…</p>
            {:else if relationshipsTab.details[view.relationship.id]!.error}
              <p class="mt-3 border-t border-border/30 pt-3 text-xs text-destructive">
                {relationshipsTab.details[view.relationship.id]!.error}
              </p>
            {:else}
              <div class="mt-3 space-y-1 border-t border-border/30 pt-3">
                {@render notesList(relationshipsTab.details[view.relationship.id], 'no notes')}
              </div>
            {/if}
          {/if}
        </div>
      {/each}
      {@render loadMoreButton(relationshipsTab, () => void loadMore(relationshipsTab))}
    {:else}
      {#if digestError}
        <div
          class="break-words rounded-lg border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
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
              Digest runs the text and image blocks below, in the order you arrange them, through the memory extractor,
              then the ELTM writer agent records the extracted facts into the store. You can paste general text, raw
              notes, or summarized facts, and interleave images — an email or a document can go in as alternating text
              and images:
            </p>
            <ul class="mt-2 list-disc space-y-1 pl-5">
              <li>First-person pronouns ("I", "my") are automatically mapped to "the user"</li>
              <li>Entities, relationships, and diary notes are extracted and recorded automatically</li>
              <li>Provide clear context and absolute dates ("the week of May 15, 2026") when possible</li>
              <li>Relative dates ("yesterday") resolve against the reference date below, or today when none is set</li>
              <li>
                The reference date only anchors the extraction — the recorded notes are always dated the day of the
                digest
              </li>
              <li>
                Images are read by the extraction model — the server's extraction model must support vision, or the
                digest fails with a clear error
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
    {/if}
  </div>
</div>
