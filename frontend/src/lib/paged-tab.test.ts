import { describe, expect, it } from 'vitest'
import { PagedTab } from './paged-tab.svelte'

interface Details {
  notes: number[]
  error?: string
}

const emptyDetails = (): Details => ({ notes: [] })
const instantDetails = async (id: number): Promise<Details> => ({ notes: [id] })

/** Fake server page source: `total` rows, honoring limit/offset like Exposed's LIMIT/OFFSET. */
function source(total: number) {
  return async (limit: number, offset: number): Promise<number[]> =>
    Array.from({ length: Math.max(0, Math.min(limit, total - offset)) }, (_, i) => offset + i)
}

/** Page source whose size is mutated between calls (resync scenarios). */
function mutableSource() {
  let total = 0
  return {
    setTotal: (n: number) => (total = n),
    fetchPage: async (limit: number, offset: number): Promise<number[]> =>
      Array.from({ length: Math.max(0, Math.min(limit, total - offset)) }, (_, i) => offset + i),
  }
}

/** Page source gated on manual resolution (for the in-flight guards). */
function gatedSource() {
  const pending: { limit: number; offset: number; resolve: (rows: number[]) => void }[] = []
  const fetchPage = (limit: number, offset: number) =>
    new Promise<number[]>((resolve) => pending.push({ limit, offset, resolve }))
  return { fetchPage, pending }
}

describe('load / canLoadMore', () => {
  it('fetches the first window and reports more pages via the probe', async () => {
    const tab = new PagedTab<number, Details>(source(250), instantDetails, emptyDetails)
    await tab.load()
    expect(tab.rows).toHaveLength(100)
    expect(tab.full).toBe(false)
    expect(tab.canLoadMore).toBe(true)
  })

  it('marks an exact-window result full (no pointless load-more)', async () => {
    const tab = new PagedTab<number, Details>(source(100), instantDetails, emptyDetails)
    await tab.load()
    expect(tab.rows).toHaveLength(100)
    expect(tab.full).toBe(true)
    expect(tab.canLoadMore).toBe(false)
  })
})

describe('loadMore', () => {
  it('appends the next page and settles full on the short tail', async () => {
    const tab = new PagedTab<number, Details>(source(250), instantDetails, emptyDetails)
    await tab.load()
    await tab.loadMore()
    expect(tab.rows).toHaveLength(200)
    expect(tab.full).toBe(false)
    await tab.loadMore()
    expect(tab.rows).toHaveLength(250)
    expect(tab.full).toBe(true)
    expect(tab.canLoadMore).toBe(false)
  })

  it('ignores a second click while a page is in flight (no duplicate pages)', async () => {
    const { fetchPage, pending } = gatedSource()
    const tab = new PagedTab<number, Details>(fetchPage, instantDetails, emptyDetails)
    tab.rows = [0, 1]
    const first = tab.loadMore()
    expect(tab.loadingMore).toBe(true)
    const second = tab.loadMore()
    expect(pending).toHaveLength(1)
    pending[0].resolve([2, 3])
    await first
    await second
    expect(tab.rows).toEqual([0, 1, 2, 3])
    expect(tab.loadingMore).toBe(false)
  })
})

describe('resync', () => {
  it('replaces the rows when the server side changed and reports success', async () => {
    const { fetchPage, setTotal } = mutableSource()
    setTotal(250)
    const tab = new PagedTab<number, Details>(fetchPage, instantDetails, emptyDetails)
    await tab.load()
    setTotal(50)
    expect(await tab.resync()).toBe(true)
    expect(tab.rows).toHaveLength(50)
    expect(tab.full).toBe(true)
  })

  it('keeps the rows when unchanged but still re-arms full via the probe', async () => {
    const { fetchPage, setTotal } = mutableSource()
    setTotal(100)
    const tab = new PagedTab<number, Details>(fetchPage, instantDetails, emptyDetails)
    await tab.load()
    expect(tab.full).toBe(true)
    // the server grew past the loaded window: the window itself is unchanged,
    // so only the probe can settle the flag (stale flag = no "load more")
    setTotal(250)
    expect(await tab.resync()).toBe(true)
    expect(tab.rows).toHaveLength(100)
    expect(tab.full).toBe(false)
    expect(tab.canLoadMore).toBe(true)
  })

  it('shrinks the loaded window when the server side shrank', async () => {
    const { fetchPage, setTotal } = mutableSource()
    setTotal(150)
    const tab = new PagedTab<number, Details>(fetchPage, instantDetails, emptyDetails)
    await tab.load()
    await tab.loadMore()
    expect(tab.rows).toHaveLength(150)
    setTotal(120)
    await tab.resync()
    expect(tab.rows).toHaveLength(120)
    expect(tab.full).toBe(true)
  })

  it('reports failure and keeps the list when the fetch throws', async () => {
    let fail = false
    const fetchPage = async (limit: number, offset: number): Promise<number[]> => {
      if (fail) throw new Error('down')
      return source(100)(limit, offset)
    }
    const tab = new PagedTab<number, Details>(fetchPage, instantDetails, emptyDetails)
    await tab.load()
    fail = true
    expect(await tab.resync()).toBe(false)
    expect(tab.rows).toHaveLength(100)
  })
})

describe('toggle / collapse', () => {
  it('expands and fetches the details payload', async () => {
    const tab = new PagedTab<number, Details>(source(0), instantDetails, emptyDetails)
    await tab.toggle(7)
    expect(tab.expanded[7]).toBe(true)
    expect(tab.details[7]).toEqual({ notes: [7] })
  })

  it('stores the error on the payload when the fetch fails', async () => {
    const tab = new PagedTab<number, Details>(
      source(0),
      async () => {
        throw new Error('boom')
      },
      emptyDetails,
    )
    await tab.toggle(7)
    expect(tab.expanded[7]).toBe(true)
    expect(tab.details[7]?.error).toBe('boom')
  })

  it('second toggle collapses and drops both the flag and the cached payload', async () => {
    const tab = new PagedTab<number, Details>(source(0), instantDetails, emptyDetails)
    await tab.toggle(7)
    tab.toggle(7)
    expect(tab.expanded[7]).toBeUndefined()
    expect(tab.details[7]).toBeUndefined()
  })

  it('a collapse racing the fetch never resurrects the payload', async () => {
    let resolve!: (d: Details) => void
    const gate = new Promise<Details>((r) => (resolve = r))
    const tab = new PagedTab<number, Details>(source(0), () => gate, emptyDetails)
    const toggling = tab.toggle(7)
    expect(tab.expanded[7]).toBe(true)
    tab.collapse(7)
    resolve({ notes: [1] })
    await toggling
    expect(tab.expanded[7]).toBeUndefined()
    expect(tab.details[7]).toBeUndefined()
  })
})

describe('refreshExpanded', () => {
  it('refetches only the expanded cards', async () => {
    const fetched: number[] = []
    const tab = new PagedTab<number, Details>(
      source(0),
      async (id) => {
        fetched.push(id)
        return instantDetails(id)
      },
      emptyDetails,
    )
    await tab.toggle(1)
    await tab.toggle(2)
    tab.collapse(1)
    fetched.length = 0
    await tab.refreshExpanded()
    expect(fetched).toEqual([2])
  })

  it('keeps the previous payload when the refetch fails', async () => {
    let fail = false
    const tab = new PagedTab<number, Details>(
      source(0),
      async (id) => {
        if (fail) throw new Error('down')
        return instantDetails(id)
      },
      emptyDetails,
    )
    await tab.toggle(7)
    fail = true
    await tab.refreshExpanded()
    expect(tab.details[7]).toEqual({ notes: [7] })
  })

  it('does not resurrect a card collapsed mid-refresh', async () => {
    let resolve!: (d: Details) => void
    const gate = new Promise<Details>((r) => (resolve = r))
    const tab = new PagedTab<number, Details>(source(0), () => gate, emptyDetails)
    tab.expanded = { 7: true }
    const refreshing = tab.refreshExpanded()
    tab.collapse(7)
    resolve({ notes: [1] })
    await refreshing
    expect(tab.details[7]).toBeUndefined()
  })
})
