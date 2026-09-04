import { afterEach, describe, expect, it, vi } from 'vitest'
import { downloadJsonFile, parseChatExportFile } from './chat-transfer'

describe('parseChatExportFile', () => {
  it('parses an exported payload', async () => {
    const file = new File([JSON.stringify({ title: 'T', messages: [{ role: 'user', parts: [] }] })], '1.json')
    expect(await parseChatExportFile(file)).toEqual({
      title: 'T',
      messages: [{ role: 'user', parts: [] }],
    })
  })

  it('rejects a non-JSON file with the file name in the error', async () => {
    const file = new File(['not json {'], 'broken.json')
    await expect(parseChatExportFile(file)).rejects.toThrow('"broken.json" is not valid JSON')
  })

  it('rejects a file missing the title/messages shape', async () => {
    const wrongObject = new File([JSON.stringify({ foo: 1 })], 'wrong.json')
    await expect(parseChatExportFile(wrongObject)).rejects.toThrow('not an exported chat file')
    // JSON.parse accepts primitives: those are not payloads either
    const primitive = new File(['"just a string"'], 'wrong2.json')
    await expect(parseChatExportFile(primitive)).rejects.toThrow('not an exported chat file')
  })

  it('rejects a payload whose title/messages have the wrong types', async () => {
    const titleNumber = new File([JSON.stringify({ title: 1, messages: [] })], 'wrong3.json')
    await expect(parseChatExportFile(titleNumber)).rejects.toThrow('not an exported chat file')
    const messagesObject = new File([JSON.stringify({ title: 't', messages: {} })], 'wrong4.json')
    await expect(parseChatExportFile(messagesObject)).rejects.toThrow('not an exported chat file')
  })
})

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
