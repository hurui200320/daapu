import { describe, expect, it } from 'vitest'
import { dataUrlToImagePart, messageSpacing, partOrdinalKey, roundSignature } from './display'
import type { ChatMessage, ChatMessagePart } from './types'

const text = (t: string): ChatMessagePart => ({ type: 'text', text: t })
const call = (tool: string, args: Record<string, unknown>): ChatMessagePart => ({
  type: 'tool_call',
  id: '',
  tool,
  args,
})
const result = (id: string): ChatMessagePart => ({
  type: 'tool_result',
  id,
  tool: 't',
  parts: [{ type: 'text', text: 'ok' }],
})

describe('roundSignature', () => {
  it('keys non-assistant roles by role only', () => {
    expect(roundSignature({ role: 'user', parts: [] })).toBe('role:user')
    // every tool_result display message shares one bucket — their collapsible
    // toggles key on the per-part result id instead
    expect(roundSignature({ role: 'tool_result', parts: [] })).toBe('role:tool_result')
    expect(roundSignature({ role: 'tool_result', parts: [result('a')] })).toBe('role:tool_result')
  })

  it('includes tool calls in order and coalesced text', () => {
    const m: ChatMessage = {
      role: 'assistant',
      parts: [text('hello '), text('world'), call('fs__read_text_file', { path: '/a' })],
    }
    const sig = roundSignature(m)
    expect(sig).toContain('"fs__read_text_file"')
    expect(sig.endsWith(':hello world')).toBe(true)
  })

  it('ignores tool_call ids and part order between types is irrelevant to identity', () => {
    // same calls/text split differently across part types must match:
    // this is exactly the stored-form vs display-commit difference after
    // the `done` reload
    const a: ChatMessage = {
      role: 'assistant',
      parts: [text('a'), text('b'), call('t1', { x: 1 })],
    }
    const b: ChatMessage = {
      role: 'assistant',
      parts: [text('ab'), call('t1', { x: 1 })],
    }
    expect(roundSignature(a)).toBe(roundSignature(b))
  })

  it('distinguishes different args of the same tool', () => {
    const a: ChatMessage = { role: 'assistant', parts: [call('t1', { x: 1 })] }
    const b: ChatMessage = { role: 'assistant', parts: [call('t1', { x: 2 })] }
    expect(roundSignature(a)).not.toBe(roundSignature(b))
  })

  it('distinguishes a new streamed round from its committed form ordering', () => {
    // args key ORDER matters for JSON.stringify: identical objects built
    // twice with the same literal keep the same signature
    const a: ChatMessage = { role: 'assistant', parts: [call('t1', { x: 1, y: 2 })] }
    const b: ChatMessage = { role: 'assistant', parts: [call('t1', { x: 1, y: 2 })] }
    expect(roundSignature(a)).toBe(roundSignature(b))
  })
})

describe('partOrdinalKey', () => {
  it('keys by type + ordinal within the type across interleaving', () => {
    const parts: ChatMessagePart[] = [text('one'), text('two'), { type: 'reasoning', content: 'r' }, text('three')]
    expect(partOrdinalKey(parts, 0)).toBe('text:0')
    expect(partOrdinalKey(parts, 1)).toBe('text:1')
    expect(partOrdinalKey(parts, 2)).toBe('reasoning:0')
    expect(partOrdinalKey(parts, 3)).toBe('text:2')
  })

  it('keys tool results on their id, not their ordinal', () => {
    const parts: ChatMessagePart[] = [result('id-9'), result('id-10')]
    expect(partOrdinalKey(parts, 0)).toBe('tool_result:id-9')
    expect(partOrdinalKey(parts, 1)).toBe('tool_result:id-10')
  })

  it('survives the batched-display → stored-message relocation', () => {
    // one display message carrying both results, then two stored messages
    // each holding one: the id-keyed override written against the display
    // part must resolve identically afterwards — key equality is IDENTITY-
    // based here, independent of each array's own arrangement
    const display: ChatMessagePart[] = [result('r1'), result('r2')]
    const storedA: ChatMessagePart[] = [text('lead-in'), result('r1')]
    const storedB: ChatMessagePart[] = [result('r2')]
    expect(partOrdinalKey(display, 0)).toBe(partOrdinalKey(storedA, 1))
    expect(partOrdinalKey(display, 1)).toBe(partOrdinalKey(storedB, 0))
  })

  it('is defensive about an out-of-range index', () => {
    expect(partOrdinalKey([], 3)).toBe('missing:3')
  })
})

describe('messageSpacing', () => {
  const assistantToolRound: ChatMessage = {
    role: 'assistant',
    parts: [call('t1', {})],
  }
  const assistantTextRound: ChatMessage = { role: 'assistant', parts: [text('hi')] }

  it('never spaces the first message', () => {
    expect(messageSpacing([assistantTextRound], 0)).toBe('')
  })

  it('spaces standalone messages with mt-8', () => {
    expect(messageSpacing([assistantTextRound, assistantTextRound], 1)).toBe('mt-8')
  })

  it('glues tool chains together', () => {
    const chain: ChatMessage[] = [
      assistantToolRound,
      { role: 'tool_result', parts: [] },
      // next round starting with tool calls stays chained to the result
      assistantToolRound,
    ]
    expect(messageSpacing(chain, 1)).toBe('')
    expect(messageSpacing(chain, 2)).toBe('')
  })

  it('breaks the chain after a plain text reply', () => {
    const chainEnd: ChatMessage[] = [assistantToolRound, { role: 'tool_result', parts: [] }, assistantTextRound]
    expect(messageSpacing(chainEnd, 2)).toBe('mt-8')
  })
})

describe('dataUrlToImagePart', () => {
  function assertAttachment(part: ChatMessagePart | null): Extract<ChatMessagePart, { type: 'attachment' }> {
    if (!part || part.type !== 'attachment') throw new Error('expected an attachment part')
    return part
  }

  it('parses a plain image data URL into the attachment shape', () => {
    expect(dataUrlToImagePart('data:image/png;base64,aGVsbG8=')).toEqual({
      type: 'attachment',
      kind: 'image',
      mimeType: 'image/png',
      content: { type: 'base64', base64: 'aGVsbG8=' },
    })
  })

  it('trims surrounding whitespace from the URL and strips payload whitespace like the backend', () => {
    const part = assertAttachment(dataUrlToImagePart('  data:image/jpeg;base64,aa bb\ncd '))
    expect(part.content.type === 'base64' && part.content.base64).toBe('aabbcd')
    expect(part.mimeType).toBe('image/jpeg')
  })

  it('keeps MIME subtypes with dots and plus signs (svg+xml)', () => {
    const part = assertAttachment(dataUrlToImagePart('data:image/svg+xml;base64,x'))
    expect(part.mimeType).toBe('image/svg+xml')
  })

  it('rejects non-image and malformed URLs', () => {
    expect(dataUrlToImagePart('data:text/plain;base64,aGk=')).toBeNull()
    expect(dataUrlToImagePart('https://example.com/cat.png')).toBeNull()
    expect(dataUrlToImagePart('not-a-data-url')).toBeNull()
  })
})
