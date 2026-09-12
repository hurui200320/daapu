import { describe, expect, it } from 'vitest'
import { parseChatMessagesFile, parseReplayKnobs } from './replay-transfer'

describe('parseChatMessagesFile', () => {
  it('parses a neutral-format messages file', async () => {
    const messages = [
      { role: 'user', parts: [{ type: 'text', text: 'hi' }], createdAt: '2026-01-01T00:00:00Z' },
      { role: 'assistant', parts: [{ type: 'text', text: 'hello' }], finishReason: 'stop' },
    ]
    const file = new File([JSON.stringify(messages)], 'chat.messages.json')
    expect(await parseChatMessagesFile(file)).toEqual(messages)
  })

  it('rejects a non-JSON file with the file name in the error', async () => {
    const file = new File(['not json {'], 'broken.json')
    await expect(parseChatMessagesFile(file)).rejects.toThrow('"broken.json" is not valid JSON')
  })

  it('rejects a non-array or empty file', async () => {
    const object = new File([JSON.stringify({ title: 'T', messages: [] })], 'export.json')
    await expect(parseChatMessagesFile(object)).rejects.toThrow('not a chat messages file')
    const empty = new File(['[]'], 'empty.json')
    await expect(parseChatMessagesFile(empty)).rejects.toThrow('not a chat messages file')
    // JSON.parse accepts primitives: those are not payloads either
    const primitive = new File(['"just a string"'], 'wrong.json')
    await expect(parseChatMessagesFile(primitive)).rejects.toThrow('not a chat messages file')
  })

  it('rejects entries without the role/parts shape', async () => {
    const wrongEntry = new File([JSON.stringify([{ role: 'user', parts: [] }, { foo: 1 }])], 'wrong2.json')
    await expect(parseChatMessagesFile(wrongEntry)).rejects.toThrow('not a chat messages file')
    const partsString = new File([JSON.stringify([{ role: 'user', parts: 'x' }])], 'wrong3.json')
    await expect(parseChatMessagesFile(partsString)).rejects.toThrow('not a chat messages file')
  })
})

describe('parseReplayKnobs', () => {
  it('parses the defaults', () => {
    expect(parseReplayKnobs('8', '3')).toEqual({ knobs: { compactionRounds: 8, contextRounds: 3 } })
  })

  it('rejects values breaking the server bounds, naming the knob', () => {
    expect(parseReplayKnobs('1', '3').error).toContain('compactionRounds')
    expect(parseReplayKnobs('8', '0').error).toContain('contextRounds')
    expect(parseReplayKnobs('', '3').error).toContain('compactionRounds')
    expect(parseReplayKnobs('8.5', '3').error).toContain('compactionRounds')
    expect(parseReplayKnobs('8', 'abc').error).toContain('contextRounds')
  })
})
