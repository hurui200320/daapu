/**
 * Paged-browse windowing for the ELTM lists: "load more" pages appended
 * client-side while the server caps a single request's row count. Extracted
 * from EltmView for unit testing — the math is easy to get subtly wrong
 * (probe rows, capped chunk walks, exact-page boundaries).
 */

/** Server-side cap of a single `/api/eltm` page (WebServer.kt MAX_ELTM_PAGE_LIMIT). */
export const LIST_LIMIT_CAP = 500

/**
 * Fetch [windowSize] rows plus one probe row (to learn whether more pages
 * exist), in chunks of at most [maxChunk] — the server rejects pages beyond
 * the cap, so a window grown past it via "load more" must be walked in
 * capped chunks instead of one growing request.
 *
 * A short-but-nonempty page ends the walk early (it is the tail). Returns
 * the sliced window plus `full`: true when no probe row arrived, i.e. an
 * exact-[windowSize] server side is already known to be the last page — no
 * pointless "load more" button.
 */
export async function fetchWindow<T>(
  fetchPage: (limit: number, offset: number) => Promise<T[]>,
  windowSize: number,
  maxChunk = LIST_LIMIT_CAP,
): Promise<{ rows: T[]; full: boolean }> {
  const rows: T[] = []
  const probe = windowSize + 1
  while (rows.length < probe) {
    const limit = Math.min(maxChunk, probe - rows.length)
    const page = await fetchPage(limit, rows.length)
    rows.push(...page)
    if (page.length < limit) break
  }
  return { rows: rows.slice(0, windowSize), full: rows.length <= windowSize }
}

/** One "load more" page of [pageSize] rows plus the probe row. */
export async function fetchMore<T>(
  fetchPage: (limit: number, offset: number) => Promise<T[]>,
  offset: number,
  pageSize = 100,
): Promise<{ rows: T[]; full: boolean }> {
  const more = await fetchPage(pageSize + 1, offset)
  return { rows: more.slice(0, pageSize), full: more.length <= pageSize }
}
