import { describe, expect, it } from 'vitest'
import { fetchMore, fetchWindow } from './paging'

/** Fake server: newest-first list of [total] rows, honoring limit/offset like Exposed's LIMIT/OFFSET. */
function fakePageSource(total: number, calls: Array<[number, number]> = []) {
  return async (limit: number, offset: number): Promise<number[]> => {
    calls.push([limit, offset])
    const start = offset
    return Array.from({ length: Math.max(0, Math.min(limit, total - start)) }, (_, i) => start + i)
  }
}

describe('fetchWindow', () => {
  it('fetches one uncapped page when the window fits and reports not-full via the probe row', async () => {
    const calls: Array<[number, number]> = []
    const { rows, full } = await fetchWindow(fakePageSource(101, calls), 100)
    expect(rows).toHaveLength(100)
    expect(full).toBe(false) // 101st row existed → more pages
    expect(calls).toEqual([[101, 0]])
  })

  it('marks an exact-size result as full (no pointless load-more)', async () => {
    const { rows, full } = await fetchWindow(fakePageSource(100), 100)
    expect(rows).toHaveLength(100)
    expect(full).toBe(true)
  })

  it('slices when the source holds fewer rows than requested', async () => {
    const { rows, full } = await fetchWindow(fakePageSource(7), 20)
    expect(rows).toEqual([0, 1, 2, 3, 4, 5, 6])
    expect(full).toBe(true)
  })

  it('handles an empty source', async () => {
    const { rows, full } = await fetchWindow(fakePageSource(0), 10)
    expect(rows).toEqual([])
    expect(full).toBe(true)
  })

  it('walks windows larger than the chunk cap in capped chunks', async () => {
    const calls: Array<[number, number]> = []
    // windowSize 1200 > cap 500: probe = 1201; chunk limits: 500 @0,
    // 500 @500, then the remaining 201 (+probe) @1000 — walk ends at 1201
    const src = fakePageSource(2000, calls)
    const { rows, full } = await fetchWindow(src, 1200, 500)
    expect(rows).toHaveLength(1200)
    expect(full).toBe(false)
    expect(calls).toEqual([
      [500, 0],
      [500, 500],
      [201, 1000],
    ])
  })

  it('stops early at a short middle chunk (tail detection)', async () => {
    const calls: Array<[number, number]> = []
    // only 600 rows exist but window asks for 1200: second chunk returns
    // 100 < 500 → walk ends there instead of hammering empty pages
    const { rows, full } = await fetchWindow(fakePageSource(600, calls), 1200, 500)
    expect(rows).toHaveLength(600)
    expect(full).toBe(true)
    expect(calls).toEqual([
      [500, 0],
      [500, 500],
    ])
  })
})

describe('fetchMore', () => {
  it('appends exactly pageSize rows and keeps loading when the probe row arrived', async () => {
    const calls: Array<[number, number]> = []
    const { rows, full } = await fetchMore(fakePageSource(250, calls), 100, 100)
    expect(rows).toHaveLength(100)
    expect(rows[0]).toBe(100)
    expect(full).toBe(false)
    expect(calls).toEqual([[101, 100]])
  })

  it('reports full on a short final page', async () => {
    const { rows, full } = await fetchMore(fakePageSource(150), 100, 100)
    expect(rows).toHaveLength(50)
    expect(full).toBe(true)
  })
})
