import { describe, expect, it } from 'vitest'
import { parseChatExportFile } from './chat-transfer'

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
