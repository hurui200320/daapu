<script lang="ts">
  import { untrack } from 'svelte'
  import { ChevronDown, ChevronRight } from '@lucide/svelte'
  import { getEntityNotes, getEntityRelationships, getRelationshipNotes, listEntities, listRelationships } from '../api'
  import type { EltmNoteDto, EntityViewDto, RelationshipViewDto } from '../types'
  import { onIntervalAndFocus } from '../resync'
  import { fetchMore, fetchWindow } from '../paging'
  import { router } from '../router.svelte'
  import { errMsg } from '../utils'
  import Button from './ui/button.svelte'

  type Tab = 'entities' | 'relationships'

  interface EntityDetails {
    relationships: RelationshipViewDto[]
    notes: EltmNoteDto[]
    error?: string
  }

  interface RelationshipDetails {
    notes: EltmNoteDto[]
    error?: string
  }

  /** Page size of the browse lists (`/api/eltm` `limit` param). */
  const PAGE_SIZE = 100

  const TABS: [Tab, string][] = [
    ['entities', 'Entities'],
    ['relationships', 'Relationships'],
  ]

  /**
   * One ELTM browse tab: the paged row window ("load more" appends
   * client-side while the server caps a request's row count) plus the
   * per-card expand flags and their lazily fetched drill-down payloads.
   * The former entities/relationships pair was two hand-maintained copies of
   * this machine; the details fetchers and empty payloads are injected, so
   * the two tabs cannot drift apart again. Details are refetched on every
   * expand AND on every background resync while a card stays expanded: the
   * extraction pipeline writes server-side, so a cached payload must not go
   * stale for as long as it is on screen.
   */
  class PagedTab<R, D extends { error?: string }> {
    rows = $state<R[]>([])
    // true once the last fetch of the list came back short: no more pages.
    // Lists are fetched with a one-row probe (PAGE_SIZE + 1), so an exact
    // PAGE_SIZE server side is already known to be the last page — no no-op
    // "load more"
    full = $state(false)
    // true while a "load more" request is in flight: without the guard a
    // double-click fires two requests at the same offset and appends both
    // page copies
    loadingMore = $state(false)
    // per-card expand flags and cached drill-down payloads, keyed by row id
    expanded = $state<Record<number, boolean>>({})
    details = $state<Record<number, D>>({})

    get canLoadMore(): boolean {
      return !this.full && this.rows.length >= PAGE_SIZE
    }

    // NOTE: no parameter properties — the Svelte compiler rejects TypeScript
    // accessibility modifiers, so the injected collaborators are declared as
    // plain fields and assigned in the constructor body
    private readonly fetchPage: (limit: number, offset: number) => Promise<R[]>
    private readonly fetchDetails: (id: number) => Promise<D>
    private readonly emptyDetails: () => D

    constructor(
      fetchPage: (limit: number, offset: number) => Promise<R[]>,
      fetchDetails: (id: number) => Promise<D>,
      emptyDetails: () => D,
    ) {
      this.fetchPage = fetchPage
      this.fetchDetails = fetchDetails
      this.emptyDetails = emptyDetails
    }

    /** Initial page load; throws on failure (the view owns the error banner). */
    async load(): Promise<void> {
      const { rows, full } = await fetchWindow(this.fetchPage, PAGE_SIZE)
      this.rows = rows
      this.full = full
    }

    /**
     * Background resync (silent): fetches the loaded window — so pages
     * appended via "load more" survive, and a window that shrank
     * server-side (merge/delete) shrinks here too — and reports whether it
     * succeeded, since the view may only clear its banner then. The probe
     * fetch always settles `full`: a server that grew past the loaded
     * window leaves the window itself unchanged, so the equality gate
     * below would skip it (stale flag = no "load more").
     */
    async resync(): Promise<boolean> {
      try {
        const { rows, full } = await fetchWindow(this.fetchPage, Math.max(PAGE_SIZE, this.rows.length))
        this.full = full
        if (JSON.stringify(rows) !== JSON.stringify(this.rows)) {
          this.rows = rows
        }
        return true
      } catch {
        // transient backend hiccup: keep the current list
        return false
      }
    }

    /** One "load more" page (double-click safe); throws on failure. */
    async loadMore(): Promise<void> {
      if (this.loadingMore) return
      this.loadingMore = true
      try {
        const { rows, full } = await fetchMore(this.fetchPage, this.rows.length)
        this.rows = [...this.rows, ...rows]
        if (full) this.full = true
      } finally {
        this.loadingMore = false
      }
    }

    /** Expand a card, fetching its drill-down payload (a failed fetch
     * renders the payload's error instead). A collapse racing the fetch
     * never resurrects the entry: the write is guarded on the flag still
     * being up (same rule as the chat history loads). */
    async toggle(id: number) {
      if (this.expanded[id]) {
        this.collapse(id)
        return
      }
      this.expanded = { ...this.expanded, [id]: true }
      try {
        const details = await this.fetchDetails(id)
        if (this.expanded[id]) this.details = { ...this.details, [id]: details }
      } catch (e) {
        if (this.expanded[id]) {
          this.details = { ...this.details, [id]: { ...this.emptyDetails(), error: errMsg(e) } }
        }
      }
    }

    /** Collapse bookkeeping: drop the id from BOTH the flag map and its cached
     * payload, so expanded cards never accumulate stale notes arrays for rows
     * that scrolled out of (or vanished from) the loaded window. */
    collapse(id: number) {
      const expanded = { ...this.expanded }
      delete expanded[id]
      const details = { ...this.details }
      delete details[id]
      this.expanded = expanded
      this.details = details
    }

    /** Refresh the drill-down payloads of the cards currently expanded (the
     * extraction pipeline may have appended notes/ended relationships); a
     * failed fetch keeps the previous payload, and a card collapsed
     * mid-fetch is not resurrected (same guard as toggle). */
    async refreshExpanded(): Promise<void> {
      const ids = Object.keys(this.expanded)
        .filter((k) => this.expanded[Number(k)])
        .map(Number)
      await Promise.all(
        ids.map(async (id) => {
          try {
            const details = await this.fetchDetails(id)
            if (this.expanded[id]) this.details = { ...this.details, [id]: details }
          } catch {
            // keep the previous payload
          }
        }),
      )
    }
  }

  const entitiesTab = new PagedTab<EntityViewDto, EntityDetails>(
    (limit, offset) => listEntities(limit, offset),
    async (id) => {
      const [relationships, notes] = await Promise.all([getEntityRelationships(id), getEntityNotes(id)])
      return { relationships, notes }
    },
    () => ({ relationships: [], notes: [] }),
  )

  const relationshipsTab = new PagedTab<RelationshipViewDto, RelationshipDetails>(
    (limit, offset) => listRelationships(limit, offset),
    async (id) => ({ notes: await getRelationshipNotes(id) }),
    () => ({ notes: [] }),
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
    // every mutation ends in refresh(): clear stale errors so a failed op
    // doesn't leave a permanent banner after later successes
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

  async function loadMoreEntities() {
    if (entitiesTab.loadingMore) return
    // clear stale errors so the list state stays the banner's source of truth
    error = null
    try {
      await entitiesTab.loadMore()
    } catch (e) {
      error = errMsg(e)
    }
  }

  async function loadMoreRelationships() {
    if (relationshipsTab.loadingMore) return
    error = null
    try {
      await relationshipsTab.loadMore()
    } catch (e) {
      error = errMsg(e)
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

{#snippet notesList(notes: EltmNoteDto[], emptyLabel: string)}
  {#if notes.length === 0}
    <p class="text-xs text-muted-foreground">{emptyLabel}</p>
  {:else}
    {#each notes as note (note.id)}
      <div class="rounded-lg border border-border/20 bg-background/40 px-3 py-2">
        <span class="text-xs text-muted-foreground tabular-nums">{note.eventDate}</span>
        <p class="whitespace-pre-wrap break-words text-sm leading-6">{note.note}</p>
      </div>
    {/each}
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
        External long-term memory: entities, relationships, and diary notes (read-only — writes are LLM-driven)
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
                  {@render notesList(entitiesTab.details[view.entity.id]?.notes ?? [], 'none')}
                </div>
              </div>
            {/if}
          {/if}
        </div>
      {/each}
      {@render loadMoreButton(entitiesTab, loadMoreEntities)}
    {:else}
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
                {@render notesList(relationshipsTab.details[view.relationship.id]?.notes ?? [], 'no notes')}
              </div>
            {/if}
          {/if}
        </div>
      {/each}
      {@render loadMoreButton(relationshipsTab, loadMoreRelationships)}
    {/if}
  </div>
</div>
