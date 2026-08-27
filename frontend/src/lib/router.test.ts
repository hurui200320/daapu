import { describe, expect, it } from 'vitest'
import { parseHash } from './routes'

describe('parseHash', () => {
  it('maps the chat home route', () => {
    expect(parseHash('#/chat')).toEqual({ name: 'chat', chatId: null })
    expect(parseHash('#/chat/')).toEqual({ name: 'chat', chatId: null })
  })

  it('extracts the chat id', () => {
    expect(parseHash('#/chat/abc-123')).toEqual({ name: 'chat', chatId: 'abc-123' })
    expect(parseHash('#/chat/abc-123/')).toEqual({ name: 'chat', chatId: 'abc-123' })
  })

  it('percent-decodes the chat id once, falling back to the raw value on malformed input', () => {
    expect(parseHash('#/chat/a%20b')).toEqual({ name: 'chat', chatId: 'a b' })
    // '%zz' is not a valid escape — kept as-is
    expect(parseHash('#/chat/a%zz')).toEqual({ name: 'chat', chatId: 'a%zz' })
  })

  it('does not swallow slash-containing ids into one segment', () => {
    // the id segment excludes '/'; deeper paths map home (only ids created
    // by this app exist, so anything else degrades gracefully to home)
    expect(parseHash('#/chat/a/b')).toEqual({ name: 'chat', chatId: null })
  })

  it('maps the eltm and personas routes with and without trailing slash', () => {
    expect(parseHash('#/eltm')).toEqual({ name: 'eltm' })
    expect(parseHash('#/eltm/')).toEqual({ name: 'eltm' })
    expect(parseHash('#/personas')).toEqual({ name: 'personas' })
    expect(parseHash('#/personas/')).toEqual({ name: 'personas' })
  })

  it('falls back to chat home for unknown or empty hashes without mutating the URL itself', () => {
    const CHAT_HOME = { name: 'chat', chatId: null }
    expect(parseHash('')).toEqual(CHAT_HOME)
    expect(parseHash('#')).toEqual(CHAT_HOME)
    expect(parseHash('#nope')).toEqual(CHAT_HOME)
    expect(parseHash('#/eltmx')).toEqual(CHAT_HOME)
  })
})
