import { describe, expect, it } from 'vitest'
import { parseReplayChatFile, parseReplayKnobs } from './replay-transfer'

describe('parseReplayChatFile', () => {
  it('parses an exported-format chat file', async () => {
    const messages = [
      { role: 'user', parts: [{ type: 'text', text: 'hi' }], createdAt: '2026-01-01T00:00:00Z' },
      { role: 'assistant', parts: [{ type: 'text', text: 'hello' }], finishReason: 'stop' },
    ]
    const file = new File([JSON.stringify({ title: 'My chat', messages })], 'chat.json')
    expect(await parseReplayChatFile(file)).toEqual({ title: 'My chat', messages })
  })

  it('accepts any title value — the replay never uses it', async () => {
    const messages = [
      { role: 'user', parts: [{ type: 'text', text: 'hi' }] },
      { role: 'assistant', parts: [{ type: 'text', text: 'hello' }], finishReason: 'stop' },
    ]
    const file = new File([JSON.stringify({ title: '', messages })], 'chat.json')
    expect(await parseReplayChatFile(file)).toEqual({ title: '', messages })
  })

  it('rejects a non-JSON file with the file name in the error', async () => {
    const file = new File(['not json {'], 'broken.json')
    await expect(parseReplayChatFile(file)).rejects.toThrow('"broken.json" is not valid JSON')
  })

  it('rejects a file without the {title, messages} payload shape', async () => {
    // a raw messages array is no longer an accepted shape
    const array = new File([JSON.stringify([{ role: 'user', parts: [] }])], 'array.json')
    await expect(parseReplayChatFile(array)).rejects.toThrow('not an exported chat file')
    // missing title
    const noTitle = new File([JSON.stringify({ messages: [] })], 'no-title.json')
    await expect(parseReplayChatFile(noTitle)).rejects.toThrow('not an exported chat file')
    // title of the wrong type
    const numberTitle = new File([JSON.stringify({ title: 7, messages: [] })], 'num.json')
    await expect(parseReplayChatFile(numberTitle)).rejects.toThrow('not an exported chat file')
    // JSON.parse accepts primitives: those are not payloads either
    const primitive = new File(['"just a string"'], 'wrong.json')
    await expect(parseReplayChatFile(primitive)).rejects.toThrow('not an exported chat file')
  })

  it('rejects an empty messages array', async () => {
    const empty = new File([JSON.stringify({ title: 'T', messages: [] })], 'empty.json')
    await expect(parseReplayChatFile(empty)).rejects.toThrow('does not carry replayable messages')
  })

  it('rejects messages without the role/parts shape', async () => {
    const wrongEntry = new File(
      [JSON.stringify({ title: 'T', messages: [{ role: 'user', parts: [] }, { foo: 1 }] })],
      'wrong2.json',
    )
    await expect(parseReplayChatFile(wrongEntry)).rejects.toThrow('does not carry replayable messages')
    const partsString = new File(
      [JSON.stringify({ title: 'T', messages: [{ role: 'user', parts: 'x' }] })],
      'wrong3.json',
    )
    await expect(parseReplayChatFile(partsString)).rejects.toThrow('does not carry replayable messages')
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
