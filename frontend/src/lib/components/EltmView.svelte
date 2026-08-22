<script lang="ts">
  import { onMount } from 'svelte'
  import { ChevronDown, ChevronRight } from '@lucide/svelte'
  import {
    getEntityNotes,
    getEntityRelationships,
    getRelationshipNotes,
    listEntities,
    listRelationships,
  } from '../api'
  import type { EltmNoteDto, EntityViewDto, RelationshipViewDto } from '../types'
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
  /**
   * Server-side cap of a single `/api/eltm` page
   * (WebServer.kt MAX_ELTM_PAGE_LIMIT): a resync chunk must never exceed it.
   */
  const LIST_LIMIT_CAP = 500

  let tab = $state<Tab>('entities')
  let entities = $state<EntityViewDto[]>([])
  let relationships = $state<RelationshipViewDto[]>([])
  let error = $state<string | null>(null)
  // true once the last fetch of a list came back short: no more pages. Lists
  // are fetched with a one-row probe (PAGE_SIZE + 1), so an exact PAGE_SIZE
  // server side is already known to be the last page — no no-op "load more"
  let entitiesFull = $state(false)
  let relationshipsFull = $state(false)
  // per-card expand flags and lazily fetched drill-down payloads (refetched
  // on every expand: the extraction pipeline writes server-side, so a cached
  // payload would go stale for the whole session — the view stays mounted)
  let expandedEntities = $state<Record<number, boolean>>({})
  let expandedRelationships = $state<Record<number, boolean>>({})
  let entityDetails = $state<Record<number, EntityDetails>>({})
  let relationshipDetails = $state<Record<number, RelationshipDetails>>({})

  async function refresh() {
    // every mutation ends in refresh(): clear stale errors so a failed op
    // doesn't leave a permanent banner after later successes
    error = null
    try {
      const [e, r] = await Promise.all([listEntities(PAGE_SIZE + 1), listRelationships(PAGE_SIZE + 1)])
      entities = e.slice(0, PAGE_SIZE)
      relationships = r.slice(0, PAGE_SIZE)
      entitiesFull = e.length <= PAGE_SIZE
      relationshipsFull = r.length <= PAGE_SIZE
    } catch (e) {
      error = String(e)
    }
  }

  /**
   * Fetch [windowSize] rows plus one probe row (to learn whether more pages
   * exist), in chunks of at most [LIST_LIMIT_CAP] — the server rejects pages
   * beyond the cap, so a window grown past it via "load more" must be walked
   * in capped chunks instead of one growing request.
   */
  async function fetchWindow<T>(
    fetchPage: (limit: number, offset: number) => Promise<T[]>,
    windowSize: number,
  ): Promise<{ rows: T[]; full: boolean }> {
    const rows: T[] = []
    const probe = windowSize + 1
    while (rows.length < probe) {
      const limit = Math.min(PAGE_SIZE, LIST_LIMIT_CAP, probe - rows.length)
      const page = await fetchPage(limit, rows.length)
      rows.push(...page)
      if (page.length < limit) break
    }
    return { rows: rows.slice(0, windowSize), full: rows.length <= windowSize }
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
    } catch {
      // transient backend hiccup: keep the current lists
    }
  }

  async function loadMoreEntities() {
    // like refresh(): clear stale errors so the list state stays the banner's
    // source of truth
    error = null
    try {
      const more = await listEntities(PAGE_SIZE + 1, entities.length)
      entities = [...entities, ...more.slice(0, PAGE_SIZE)]
      if (more.length <= PAGE_SIZE) entitiesFull = true
    } catch (e) {
      error = String(e)
    }
  }

  async function loadMoreRelationships() {
    error = null
    try {
      const more = await listRelationships(PAGE_SIZE + 1, relationships.length)
      relationships = [...relationships, ...more.slice(0, PAGE_SIZE)]
      if (more.length <= PAGE_SIZE) relationshipsFull = true
    } catch (e) {
      error = String(e)
    }
  }

  onMount(() => {
    void refresh()
    const interval = setInterval(() => void resync(), 30_000)
    window.addEventListener('focus', resync)
    return () => {
      clearInterval(interval)
      window.removeEventListener('focus', resync)
    }
  })

  async function toggleEntity(id: number) {
    if (expandedEntities[id]) {
      expandedEntities = { ...expandedEntities, [id]: false }
      return
    }
    expandedEntities = { ...expandedEntities, [id]: true }
    try {
      const [rels, notes] = await Promise.all([getEntityRelationships(id), getEntityNotes(id)])
      entityDetails = { ...entityDetails, [id]: { relationships: rels, notes } }
    } catch (e) {
      entityDetails = {
        ...entityDetails,
        [id]: { relationships: [], notes: [], error: String(e) },
      }
    }
  }

  async function toggleRelationship(id: number) {
    if (expandedRelationships[id]) {
      expandedRelationships = { ...expandedRelationships, [id]: false }
      return
    }
    expandedRelationships = { ...expandedRelationships, [id]: true }
    try {
      const notes = await getRelationshipNotes(id)
      relationshipDetails = { ...relationshipDetails, [id]: { notes } }
    } catch (e) {
      relationshipDetails = { ...relationshipDetails, [id]: { notes: [], error: String(e) } }
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
      <div class="break-words rounded-lg border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
        {error}
      </div>
    {/if}

    {#if tab === 'entities'}
      {#if entities.length === 0}
        <div class="py-10 text-center text-sm text-muted-foreground">no entities yet</div>
      {/if}

      {#each entities as view}
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
              {#each Object.entries(view.attributes) as [key, value]}
                <span
                  class="rounded-full border border-border/30 bg-background/40 px-2 py-0.5 text-xs"
                >
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
              <p class="mt-3 border-t border-border/30 pt-3 text-xs text-muted-foreground">
                loading…
              </p>
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
                    {#each entityDetails[view.entity.id]?.relationships ?? [] as rel}
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
                    {#each entityDetails[view.entity.id]?.notes ?? [] as note}
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
          <Button size="sm" variant="ghost" onclick={loadMoreEntities}>Load more</Button>
        </div>
      {/if}
    {:else}
      {#if relationships.length === 0}
        <div class="py-10 text-center text-sm text-muted-foreground">no relationships yet</div>
      {/if}

      {#each relationships as view}
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
                <span class="shrink-0 rounded-full bg-accent px-2 py-0.5 text-xs text-accent-foreground">
                  active
                </span>
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
              <p class="mt-3 border-t border-border/30 pt-3 text-xs text-muted-foreground">
                loading…
              </p>
            {:else if relationshipDetails[view.relationship.id]!.error}
              <p class="mt-3 border-t border-border/30 pt-3 text-xs text-destructive">
                {relationshipDetails[view.relationship.id]!.error}
              </p>
            {:else}
              <div class="mt-3 space-y-1 border-t border-border/30 pt-3">
                {#if relationshipDetails[view.relationship.id]?.notes.length === 0}
                  <p class="text-xs text-muted-foreground">no notes</p>
                {:else}
                  {#each relationshipDetails[view.relationship.id]?.notes ?? [] as note}
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
          <Button size="sm" variant="ghost" onclick={loadMoreRelationships}>Load more</Button>
        </div>
      {/if}
    {/if}
  </div>
</div>
