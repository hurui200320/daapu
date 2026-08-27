/**
 * One ELTM browse tab (extracted from EltmView.svelte as a rune module so
 * the paging/expand state machine is unit-testable without a component —
 * paged-tab.test.ts): the paged row window ("load more" appends
 * client-side while the server caps a request's row count) plus the
 * per-card expand flags and their lazily fetched drill-down payloads.
 * The former entities/relationships pair was two hand-maintained copies of
 * this machine; the details fetchers and empty payloads are injected, so
 * the two tabs cannot drift apart again. Details are refetched on every
 * expand AND on every background resync while a card stays expanded: the
 * extraction pipeline writes server-side, so a cached payload must not go
 * stale for as long as it is on screen.
 */
import { fetchMore, fetchWindow } from './paging'
import { errMsg, jsonEquals } from './utils'

/** Page size of the browse lists (`/api/eltm` `limit` param). */
export const PAGE_SIZE = 100

export class PagedTab<R, D extends { error?: string }> {
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
      if (!jsonEquals(rows, this.rows)) {
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
      const { rows, full } = await fetchMore(this.fetchPage, this.rows.length, PAGE_SIZE)
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
