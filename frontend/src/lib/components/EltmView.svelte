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

  let tab = $state<Tab>('entities')
  let entities = $state<EntityViewDto[]>([])
  let relationships = $state<RelationshipViewDto[]>([])
  let error = $state<string | null>(null)
  // true until the FIRST list fetch settles: the empty states must not flash
  // "no entities yet" while that first load is in flight
  let initialLoading = $state(true)
  // false until the view's first visit: the first visit loads the browse
  // window, later visits resync (keeping the loaded pages)
  let loadedOnce = false
  // true once the last fetch of a list came back short: no more pages. Lists
  // are fetched with a one-row probe (PAGE_SIZE + 1), so an exact PAGE_SIZE
  // server side is already known to be the last page — no no-op "load more"
  let entitiesFull = $state(false)
  let relationshipsFull = $state(false)
  // true while a "load more" request is in flight: without the guard a
  // double-click fires two requests at the same offset and appends both
  // page copies
  let loadingMore = $state(false)
  // per-card expand flags and lazily fetched drill-down payloads. Details are
  // refetched on every expand AND on every background resync while a card
  // stays expanded: the extraction pipeline writes server-side, so a cached
  // payload must not go stale for as long as it is on screen
  let expandedEntities = $state<Record<number, boolean>>({})
  let expandedRelationships = $state<Record<number, boolean>>({})
  let entityDetails = $state<Record<number, EntityDetails>>({})
  let relationshipDetails = $state<Record<number, RelationshipDetails>>({})

  async function refresh() {
    // every mutation ends in refresh(): clear stale errors so a failed op
    // doesn't leave a permanent banner after later successes
    error = null
    try {
      const [e, r] = await Promise.all([
        fetchWindow(listEntities, PAGE_SIZE),
        fetchWindow(listRelationships, PAGE_SIZE),
      ])
      entities = e.rows
      relationships = r.rows
      entitiesFull = e.full
      relationshipsFull = r.full
    } catch (e) {
      error = errMsg(e)
    } finally {
      initialLoading = false
    }
  }

  /**
   * Background resync (30s + window focus): the extraction pipeline writes to
   * the ELTM server-side, so the view must refresh on its own. Silent on
   * failure and replaces a list only when it actually changed, so an expanded
   * card keeps its payload. The fetch covers the currently loaded window
   * (`max(PAGE_SIZE, list length)`) plus the probe row, so pages appended via
   * "load more" survive a resync — and a window that shrank server-side
   * (merge/delete) shrinks here too.
   */
  async function resync() {
    try {
      const entityLimit = Math.max(PAGE_SIZE, entities.length)
      const relationshipLimit = Math.max(PAGE_SIZE, relationships.length)
      const [e, r] = await Promise.all([
        fetchWindow(listEntities, entityLimit),
        fetchWindow(listRelationships, relationshipLimit),
      ])
      // the fetch succeeded: a stale banner (e.g. the first visit's failed
      // load) is resolved; a failed fetch keeps the current lists and any
      // existing banner
      error = null
      // the probe fetch always settles the full flag: a server that grew
      // past the loaded window leaves the window itself unchanged, so the
      // equality gates below would skip it (stale flag = no "load more")
      entitiesFull = e.full
      relationshipsFull = r.full
      if (JSON.stringify(e.rows) !== JSON.stringify(entities)) {
        entities = e.rows
      }
      if (JSON.stringify(r.rows) !== JSON.stringify(relationships)) {
        relationships = r.rows
      }
      // refresh the drill-down payloads of the cards currently expanded (the
      // extraction pipeline may have appended notes/ended relationships); a
      // failed fetch keeps the previous payload
      await refreshExpandedDetails()
    } catch {
      // transient backend hiccup: keep the current lists
    }
  }

  async function refreshExpandedDetails() {
    const entityIds = Object.keys(expandedEntities).filter((k) => expandedEntities[Number(k)])
    const relationshipIds = Object.keys(expandedRelationships).filter((k) => expandedRelationships[Number(k)])
    await Promise.all([
      ...entityIds.map(async (k) => {
        const id = Number(k)
        try {
          const [rels, notes] = await Promise.all([getEntityRelationships(id), getEntityNotes(id)])
          entityDetails = { ...entityDetails, [id]: { relationships: rels, notes } }
        } catch {
          // keep the previous payload
        }
      }),
      ...relationshipIds.map(async (k) => {
        const id = Number(k)
        try {
          const notes = await getRelationshipNotes(id)
          relationshipDetails = { ...relationshipDetails, [id]: { notes } }
        } catch {
          // keep the previous payload
        }
      }),
    ])
  }

  async function loadMoreEntities() {
    // like refresh(): clear stale errors so the list state stays the banner's
    // source of truth
    if (loadingMore) return
    loadingMore = true
    error = null
    try {
      const { rows, full } = await fetchMore(listEntities, entities.length)
      entities = [...entities, ...rows]
      if (full) entitiesFull = true
    } catch (e) {
      error = errMsg(e)
    } finally {
      loadingMore = false
    }
  }

  async function loadMoreRelationships() {
    if (loadingMore) return
    loadingMore = true
    error = null
    try {
      const { rows, full } = await fetchMore(listRelationships, relationships.length)
      relationships = [...relationships, ...rows]
      if (full) relationshipsFull = true
    } catch (e) {
      error = errMsg(e)
    } finally {
      loadingMore = false
    }
  }

  // Fetch + poll only while the view is visible (it stays mounted, CSS-hidden
  // on the other routes): the first visit loads the browse window, later
  // visits resync — and the 30s/focus cadence runs only while it is on
  // screen, instead of polling an ELTM page the user never opens. The fetch
  // calls read reactive state (entities/relationships lengths, the expanded
  // maps) before their first await, so they must run inside `untrack`: an
  // effect may depend on the route alone — a completed fetch or a card
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

  /** Collapse bookkeeping: drop the id from BOTH the flag map and its cached
   * payload, so expanded cards never accumulate stale notes arrays for rows
   * that scrolled out of (or vanished from) the loaded window. */
  function collapse<T>(
    flags: Record<number, boolean>,
    details: Record<number, T>,
    id: number,
  ): { flags: Record<number, boolean>; details: Record<number, T> } {
    const f = { ...flags }
    delete f[id]
    const d = { ...details }
    delete d[id]
    return { flags: f, details: d }
  }

  async function toggleEntity(id: number) {
    if (expandedEntities[id]) {
      ;({ flags: expandedEntities, details: entityDetails } = collapse(expandedEntities, entityDetails, id))
      return
    }
    expandedEntities = { ...expandedEntities, [id]: true }
    try {
      const [rels, notes] = await Promise.all([getEntityRelationships(id), getEntityNotes(id)])
      entityDetails = { ...entityDetails, [id]: { relationships: rels, notes } }
    } catch (e) {
      entityDetails = {
        ...entityDetails,
        [id]: { relationships: [], notes: [], error: errMsg(e) },
      }
    }
  }

  async function toggleRelationship(id: number) {
    if (expandedRelationships[id]) {
      ;({ flags: expandedRelationships, details: relationshipDetails } = collapse(
        expandedRelationships,
        relationshipDetails,
        id,
      ))
      return
    }
    expandedRelationships = { ...expandedRelationships, [id]: true }
    try {
      const notes = await getRelationshipNotes(id)
      relationshipDetails = { ...relationshipDetails, [id]: { notes } }
    } catch (e) {
      relationshipDetails = { ...relationshipDetails, [id]: { notes: [], error: errMsg(e) } }
    }
  }
</script>

<div class="h-full overflow-y-auto">
  <div class="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-8">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">ELTM</h1>
      <p class="text-sm text-muted-foreground">
        External long-term memory: entities, relationships, and diary notes (read-only — writes are LLM-driven)
      </p>
    </div>

    <div class="flex gap-2">
      <Button
        size="sm"
        variant="ghost"
        class={tab === 'entities' ? 'bg-accent text-accent-foreground' : ''}
        onclick={() => (tab = 'entities')}
      >
        Entities
      </Button>
      <Button
        size="sm"
        variant="ghost"
        class={tab === 'relationships' ? 'bg-accent text-accent-foreground' : ''}
        onclick={() => (tab = 'relationships')}
      >
        Relationships
      </Button>
    </div>

    {#if error}
      <div
        class="break-words rounded-lg border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
      >
        {error}
      </div>
    {/if}

    {#if tab === 'entities'}
      {#if entities.length === 0 && !initialLoading && !error}
        <div class="py-10 text-center text-sm text-muted-foreground">no entities yet</div>
      {/if}

      {#each entities as view (view.entity.id)}
        <div class="rounded-2xl border border-border/30 bg-muted/60 p-4 shadow-sm backdrop-blur-md">
          <button
            class="flex w-full items-center justify-between gap-2 text-left"
            onclick={() => toggleEntity(view.entity.id)}
          >
            <span class="flex min-w-0 items-center gap-2">
              {#if expandedEntities[view.entity.id]}
                <ChevronDown class="size-4 shrink-0 text-muted-foreground" />
              {:else}
                <ChevronRight class="size-4 shrink-0 text-muted-foreground" />
              {/if}
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
            <p class="mt-1 line-clamp-2 pl-6 text-xs text-muted-foreground">
              <span class="tabular-nums">{view.latestNote.eventDate}</span> — {view.latestNote.note}
            </p>
          {/if}
          {#if expandedEntities[view.entity.id]}
            {#if !entityDetails[view.entity.id]}
              <p class="mt-3 border-t border-border/30 pt-3 text-xs text-muted-foreground">loading…</p>
            {:else if entityDetails[view.entity.id]!.error}
              <p class="mt-3 border-t border-border/30 pt-3 text-xs text-destructive">
                {entityDetails[view.entity.id]!.error}
              </p>
            {:else}
              <div class="mt-3 space-y-3 border-t border-border/30 pt-3">
                <div>
                  <div class="mb-1 text-xs font-medium text-muted-foreground">relationships</div>
                  {#if entityDetails[view.entity.id]?.relationships.length === 0}
                    <p class="text-xs text-muted-foreground">none</p>
                  {:else}
                    {#each entityDetails[view.entity.id]?.relationships ?? [] as rel (rel.relationship.id)}
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
                  {#if entityDetails[view.entity.id]?.notes.length === 0}
                    <p class="text-xs text-muted-foreground">none</p>
                  {:else}
                    {#each entityDetails[view.entity.id]?.notes ?? [] as note (note.id)}
                      <div class="rounded-lg border border-border/20 bg-background/40 px-3 py-2">
                        <span class="text-xs text-muted-foreground tabular-nums">{note.eventDate}</span>
                        <p class="whitespace-pre-wrap break-words text-sm leading-6">{note.note}</p>
                      </div>
                    {/each}
                  {/if}
                </div>
              </div>
            {/if}
          {/if}
        </div>
      {/each}
      {#if !entitiesFull && entities.length >= PAGE_SIZE}
        <div class="flex justify-center">
          <Button size="sm" variant="ghost" disabled={loadingMore} onclick={loadMoreEntities}>Load more</Button>
        </div>
      {/if}
    {:else}
      {#if relationships.length === 0 && !initialLoading && !error}
        <div class="py-10 text-center text-sm text-muted-foreground">no relationships yet</div>
      {/if}

      {#each relationships as view (view.relationship.id)}
        <div class="rounded-2xl border border-border/30 bg-muted/60 p-4 shadow-sm backdrop-blur-md">
          <button
            class="flex w-full items-center justify-between gap-2 text-left"
            onclick={() => toggleRelationship(view.relationship.id)}
          >
            <span class="flex min-w-0 items-center gap-2">
              {#if expandedRelationships[view.relationship.id]}
                <ChevronDown class="size-4 shrink-0 text-muted-foreground" />
              {:else}
                <ChevronRight class="size-4 shrink-0 text-muted-foreground" />
              {/if}
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
            <p class="mt-1 line-clamp-2 pl-6 text-xs text-muted-foreground">
              <span class="tabular-nums">{view.latestNote.eventDate}</span> — {view.latestNote.note}
            </p>
          {/if}
          {#if expandedRelationships[view.relationship.id]}
            {#if !relationshipDetails[view.relationship.id]}
              <p class="mt-3 border-t border-border/30 pt-3 text-xs text-muted-foreground">loading…</p>
            {:else if relationshipDetails[view.relationship.id]!.error}
              <p class="mt-3 border-t border-border/30 pt-3 text-xs text-destructive">
                {relationshipDetails[view.relationship.id]!.error}
              </p>
            {:else}
              <div class="mt-3 space-y-1 border-t border-border/30 pt-3">
                {#if relationshipDetails[view.relationship.id]?.notes.length === 0}
                  <p class="text-xs text-muted-foreground">no notes</p>
                {:else}
                  {#each relationshipDetails[view.relationship.id]?.notes ?? [] as note (note.id)}
                    <div class="rounded-lg border border-border/20 bg-background/40 px-3 py-2">
                      <span class="text-xs text-muted-foreground tabular-nums">{note.eventDate}</span>
                      <p class="whitespace-pre-wrap break-words text-sm leading-6">{note.note}</p>
                    </div>
                  {/each}
                {/if}
              </div>
            {/if}
          {/if}
        </div>
      {/each}
      {#if !relationshipsFull && relationships.length >= PAGE_SIZE}
        <div class="flex justify-center">
          <Button size="sm" variant="ghost" disabled={loadingMore} onclick={loadMoreRelationships}>Load more</Button>
        </div>
      {/if}
    {/if}
  </div>
</div>
