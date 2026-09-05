<script lang="ts">
  import { untrack } from 'svelte'
  import { ChevronDown, ChevronRight, Download, Loader2, Upload } from '@lucide/svelte'
  import {
    ELTM_DRILLDOWN_LIMIT,
    exportEltm,
    getEntityNotes,
    getEntityRelationships,
    getRelationshipNotes,
    importEltm,
    listEntities,
    listRelationships,
  } from '../api'
  import type { EltmExportPayload, EltmNoteDto, EntityViewDto, RelationshipViewDto } from '../types'
  import { parseEltmImportFile } from '../eltm-transfer'
  import { onIntervalAndFocus } from '../resync'
  import { PagedTab } from '../paged-tab.svelte'
  import { router } from '../router.svelte'
  import { toastStore } from '../toast-store.svelte'
  import { downloadJsonFile, errMsg } from '../utils'
  import ImportEltmDialog from './ImportEltmDialog.svelte'
  import Button from './ui/button.svelte'
  import DigestForm from './DigestForm.svelte'

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
    // success path resync instead — see DigestForm.svelte's submitDigest):
    // replace both lists and clear any stale error banner
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

  // ---- Transfer (export / import-merge): the header buttons. Export
  // downloads one `eltm.json` (the whole store, see EltmTransferService).
  // Import parses the picked file (shape check in eltm-transfer.ts), shows
  // the confirm dialog (the overwrite-attributes decision), then POSTs the
  // payload verbatim — the merge is fail-fast partial, so even a failure
  // leaves earlier writes stuck and the lists must resync either way.
  let exporting = $state(false)
  let importing = $state(false)
  let importInput = $state<HTMLInputElement | null>(null)
  // the parsed file waiting on the confirm dialog
  let pendingImport = $state<EltmExportPayload | null>(null)

  async function exportAll() {
    if (exporting) return
    exporting = true
    try {
      const payload = await exportEltm()
      // the name is hardcoded because fetch does not act on the backend's
      // Content-Disposition — keep it in sync with the export route
      // (EltmRoute.kt), like the persona store does with PersonasRoute.kt
      downloadJsonFile('eltm.json', JSON.stringify(payload))
    } catch (e) {
      toastStore.pushError(e)
    } finally {
      exporting = false
    }
  }

  function onImportPicked(input: HTMLInputElement) {
    const file = input.files?.[0]
    // reset so picking the same file again still fires the change event
    input.value = ''
    if (!file) return
    parseEltmImportFile(file)
      .then((payload) => (pendingImport = payload))
      .catch((e) => toastStore.pushError(e))
  }

  async function confirmImport(overwriteAttr: boolean) {
    const payload = pendingImport
    if (!payload || importing) return
    importing = true
    try {
      const s = await importEltm(payload, overwriteAttr)
      toastStore.push(
        `ELTM imported: ${s.entitiesCreated} entities created, ${s.entitiesMatched} matched; ` +
          `${s.relationshipsCreated} relationships created, ${s.relationshipsMatched} matched; ` +
          `${s.notesInserted} notes added, ${s.notesSkipped} duplicates skipped; ` +
          `${s.attributesWritten} attributes written, ${s.attributesKept} kept`,
      )
      pendingImport = null
    } catch (e) {
      toastStore.pushError(e)
    } finally {
      // a failed merge keeps what it already wrote (fail-fast partial), so
      // the lists resync on success AND failure (see resync: it keeps the
      // loaded pages and the expanded cards). Guarded so the busy reset
      // can never be skipped: importing gates both transfer buttons.
      try {
        await resync()
      } catch {
        // resync owns its fetch failures; nothing left to report here
      }
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
    <div class="flex items-start justify-between gap-2">
      <div>
        <h1 class="text-2xl font-semibold tracking-tight">ELTM</h1>
        <p class="text-sm text-muted-foreground">
          External long-term memory: entities, relationships, and diary notes (browse is read-only — the writer agent
          writes, see Digest)
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <!-- labeled buttons: the tray icons alone read backwards (import =
             arrow INTO the tray, which is Lucide's Download), so the text
             carries the meaning — same recipe as PersonaView's importer -->
        <Button
          variant="ghost"
          size="sm"
          disabled={importing || exporting}
          title="Import (merge) memory from an exported file"
          onclick={() => importInput?.click()}
        >
          {#if importing}
            <Loader2 class="size-4 animate-spin" />
          {:else}
            <Download class="size-4" />
          {/if}
          Import
        </Button>
        <Button
          variant="ghost"
          size="sm"
          disabled={importing || exporting}
          title="Export all memory to one JSON file"
          onclick={() => void exportAll()}
        >
          {#if exporting}
            <Loader2 class="size-4 animate-spin" />
          {:else}
            <Upload class="size-4" />
          {/if}
          Export
        </Button>
      </div>
    </div>
    <input
      bind:this={importInput}
      type="file"
      accept=".json,application/json"
      class="hidden"
      onchange={(e) => onImportPicked(e.currentTarget)}
    />
    <ImportEltmDialog
      open={pendingImport !== null}
      onClose={() => (pendingImport = null)}
      entityCount={Object.keys(pendingImport?.entities ?? {}).length}
      relationshipCount={pendingImport?.relationships.length ?? 0}
      busy={importing}
      onConfirm={(overwriteAttr) => void confirmImport(overwriteAttr)}
    />

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
    {/if}

    <!-- the Digest tab stays MOUNTED even when hidden (display:none): the
         digest draft must survive switching tabs — and chats (this view
         itself stays mounted, CSS-hidden, on other routes) — so the form
         can never be {#if}-mounted (see DigestForm.svelte). While visible,
         the wrapper only carries the tab column's gap spacing. -->
    <div class={tab === 'digest' ? 'flex flex-col gap-4' : 'hidden'}>
      <DigestForm onsubmitted={resync} />
    </div>
  </div>
</div>
