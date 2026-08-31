<script lang="ts">
  import { untrack } from 'svelte'
  import { ChevronDown, ChevronRight, Info } from '@lucide/svelte'
  import {
    ELTM_DRILLDOWN_LIMIT,
    getEntityNotes,
    getEntityRelationships,
    getRelationshipNotes,
    importEltmText,
    listEntities,
    listRelationships,
  } from '../api'
  import type { EltmNoteDto, EntityViewDto, RelationshipViewDto } from '../types'
  import { onIntervalAndFocus } from '../resync'
  import { PagedTab } from '../paged-tab.svelte'
  import { router } from '../router.svelte'
  import { errMsg } from '../utils'
  import Button from './ui/button.svelte'

  type Tab = 'entities' | 'relationships' | 'import'

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
    ['import', 'Import'],
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
    // the first visit's from-scratch load (later visits and the import
    // success path resync instead — see submitImport): replace both lists
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

  // ---- Import tab (the manual write path): a caller-supplied piece of
  // text run through the memory extraction one-shot and then the ELTM
  // writer agent. The request blocks for both stages (minutes are normal),
  // so the form is its own state machine — `importing` disables everything
  // and the busy label explains the wait. Lives in the always-mounted
  // view, so the draft survives switching tabs/chats mid-write.
  let text = $state('')
  let importDate = $state('')
  let importing = $state(false)
  let importError = $state<string | null>(null)
  let importSuccess = $state(false)

  // the extractor's skip sentinel (MemoryExtractionService.kt owns the
  // canonical value and the tolerant match, isNothingToRemember): the
  // server no-ops a text matching this sentence without any LLM call
  const NOTHING_TO_REMEMBER = 'Nothing worth remember.'

  // the reference date picker's ceiling: the browser's local today, rendered
  // the way the backend parses it (YYYY-MM-DD, see EltmRoute.kt). Page-load
  // time only — the server's future-date 400 stays the authority.
  const TODAY = (() => {
    const d = new Date()
    const mm = String(d.getMonth() + 1).padStart(2, '0')
    const dd = String(d.getDate()).padStart(2, '0')
    return `${d.getFullYear()}-${mm}-${dd}`
  })()

  // two snippets of casual first-person notes — the shape the textarea
  // encourages (any prose works; see the notice above it)
  const TEXT_PLACEHOLDER =
    'I went to Paris the week of May 15, 2026 for a work conference. It was amazing!\n' +
    'Also, I switched from editor A to editor B yesterday because of plugin compatibility.'

  async function submitImport() {
    if (importing) return
    const batch = text.trim()
    if (batch.length === 0) return
    importing = true
    importError = null
    importSuccess = false
    try {
      // no date = the server's today as the reference date (it only
      // anchors the extraction — the writer always stamps the import day)
      await importEltmText(batch, importDate || undefined)
      importSuccess = true
      // the server no longer reports whether anything was recorded (the
      // response is a bare 201): a no-op (a pasted sentinel or an empty
      // extraction) is an indistinguishable success, so the draft always
      // clears. The optional reference date deliberately stays — a
      // same-day follow-up import is the common case.
      // The browse lists pick up the new records via the background resync
      // path: unlike refresh() it keeps the loaded pages and the expanded
      // cards' drill-downs (a from-scratch load would collapse them back to
      // page 1) and leaves the lists untouched when a fetch fails.
      text = ''
      await resync()
    } catch (e) {
      importError = errMsg(e)
    } finally {
      importing = false
    }
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

<div class="h-full overflow-y-auto">
  <div class="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-8">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">ELTM</h1>
      <p class="text-sm text-muted-foreground">
        External long-term memory: entities, relationships, and diary notes (browse is read-only — the writer agent
        writes, see Import)
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
      {#if importError}
        <div
          class="break-words rounded-lg border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          {importError}
        </div>
      {/if}

      {#if importSuccess}
        <div class="rounded-lg border border-border/30 bg-muted/40 px-3 py-2 text-sm text-muted-foreground">
          Import finished — new records, if any, are on the Entities and Relationships tabs.
        </div>
      {/if}

      <!-- The notice: the endpoint runs the text through the memory
           extraction one-shot and then the ELTM writer agent (see
           EltmRoute.kt), so any prose works — the notice pins what the
           extraction stage does with it. -->
      <div class="rounded-2xl border border-border/30 bg-muted/60 p-4 shadow-sm backdrop-blur-md">
        <div class="flex items-start gap-2">
          <Info class="mt-0.5 size-4 shrink-0 text-muted-foreground" />
          <div class="min-w-0 text-sm text-muted-foreground">
            <p>
              Import runs your text through the memory extractor, then the ELTM writer agent records the extracted facts
              into the store. You can paste general text, raw notes, or summarized facts:
            </p>
            <ul class="mt-2 list-disc space-y-1 pl-5">
              <li>First-person pronouns ("I", "my") are automatically mapped to "the user"</li>
              <li>Entities, relationships, and diary notes are extracted and recorded automatically</li>
              <li>Provide clear context and absolute dates ("the week of May 15, 2026") when possible</li>
              <li>Relative dates ("yesterday") resolve against the reference date below, or today when none is set</li>
              <li>
                The reference date only anchors the extraction — the recorded notes are always dated the day of the
                import
              </li>
              <li>A text that is just "{NOTHING_TO_REMEMBER}" is treated as empty (no-op)</li>
            </ul>
          </div>
        </div>
      </div>

      <div class="flex flex-col gap-3">
        <textarea
          class="min-h-40 w-full resize-y whitespace-pre-wrap break-words rounded-xl border border-border/30 bg-background/60 p-3 text-sm leading-6 outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:opacity-50"
          aria-label="Text to import"
          placeholder={TEXT_PLACEHOLDER}
          bind:value={text}
          disabled={importing}></textarea>
        <div class="flex flex-wrap items-center justify-between gap-3">
          <label class="flex items-center gap-2 text-xs text-muted-foreground">
            Reference date (optional)
            <input
              type="date"
              class="rounded-md border border-border/30 bg-background/60 px-2 py-1 text-sm disabled:opacity-50"
              max={TODAY}
              bind:value={importDate}
              disabled={importing}
            />
          </label>
          <Button size="sm" disabled={importing || text.trim().length === 0} onclick={() => void submitImport()}>
            {importing ? 'Writing to the ELTM… this can take a while' : 'Import'}
          </Button>
        </div>
      </div>
    {/if}
  </div>
</div>
