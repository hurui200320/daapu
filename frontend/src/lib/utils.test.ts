import { afterEach, describe, expect, it, vi } from 'vitest'
import { downloadJsonFile } from './utils'

describe('downloadJsonFile', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('clicks an anchor on a JSON blob URL named for the download, then revokes the URL', () => {
    const clicks: { href: string; download: string }[] = []
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:uid'),
      revokeObjectURL: vi.fn(),
    })
    let anchor: { href: string; download: string; click: () => void } | undefined
    vi.stubGlobal('document', {
      createElement: vi.fn(() => {
        anchor = {
          href: '',
          download: '',
          click() {
            clicks.push({ href: anchor!.href, download: anchor!.download })
          },
        }
        return anchor
      }),
    })

    downloadJsonFile('1712-345.json', '{"a":1}')

    expect(clicks).toEqual([{ href: 'blob:uid', download: '1712-345.json' }])
    expect(URL.createObjectURL).toHaveBeenCalledExactlyOnceWith(new Blob(['{"a":1}'], { type: 'application/json' }))
    expect(URL.revokeObjectURL).toHaveBeenCalledExactlyOnceWith('blob:uid')
  })
})
