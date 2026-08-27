import { describe, expect, it, vi } from 'vitest'

/**
 * The markdown pipeline loads four heavy deps lazily inside `init()`; the
 * tests below mock them all so a fresh module (and its cached singleton)
 * can be instantiated per case without any real library or DOM.
 *
 * Failure injection goes through the mocked DOMPurify hook registration
 * (init calls it exactly once per attempt): the first `getMarkdownRenderer`
 * call rejects there, which must EVICT the cache instead of storing the
 * rejected promise — otherwise one failed chunk load would leave every
 * message blank until a full page reload.
 */
const purifyState = vi.hoisted(() => ({ addHookCalls: 0, failAddHook: false }))
vi.mock('dompurify', () => ({
  default: {
    addHook(): void {
      purifyState.addHookCalls++
      if (purifyState.failAddHook) throw new Error('boom')
    },
    sanitize(html: string): string {
      return html
    },
  },
}))
vi.mock('marked', () => ({
  marked: {
    // the code/link chrome is attached onto an instance; a bare class is fine
    Renderer: class {},
    use(): void {},
    parse(text: string): string {
      return text
    },
  },
}))
vi.mock('highlight.js/lib/common', () => ({ default: { getLanguage: (): boolean => false } }))
vi.mock('katex', () => ({ default: { renderToString: (): string => 'MATH' } }))
vi.mock('highlight.js/styles/github-dark.css', () => ({}))
vi.mock('katex/dist/katex.min.css', () => ({}))

async function freshRenderer() {
  vi.resetModules()
  const mod = await import('./markdown-renderer')
  return mod.getMarkdownRenderer
}

describe('markdown-renderer singleton', () => {
  it('initializes once and renders through the pipeline', async () => {
    const before = purifyState.addHookCalls
    const get = await freshRenderer()
    const render = await get()
    expect(purifyState.addHookCalls).toBe(before + 1)
    // a second call must hit the cache: no second init/hook registration
    await expect(get()).resolves.toBe(render)
    expect(purifyState.addHookCalls).toBe(before + 1)
    expect(render('plain *text*')).toBe('plain *text*')
  })

  it('evicts a failed init so the next caller retries instead of hanging forever', async () => {
    const before = purifyState.addHookCalls
    purifyState.failAddHook = true
    const get = await freshRenderer()
    await expect(get()).rejects.toThrow('boom')
    expect(purifyState.addHookCalls).toBe(before + 1)
    // recovery on the SAME accessor: the eviction re-runs init rather than
    // re-serving the rejected promise
    purifyState.failAddHook = false
    const render = await get()
    expect(purifyState.addHookCalls).toBe(before + 2)
    expect(render('recovered')).toBe('recovered')
  })
})
