import { describe, expect, it } from 'vitest'
import { hasDigestInput, moveToSlot, newTextPart, wireDigestParts, type DigestDraftPart } from './digest-form'

const text = (t: string): DigestDraftPart => ({ kind: 'text', text: t })
const image = (dataUrl: string): DigestDraftPart => ({ kind: 'image', dataUrl })

describe('moveToSlot', () => {
  const list = ['a', 'b', 'c', 'd']

  it('moves an element up one slot (up button: slot i-1)', () => {
    expect(moveToSlot(list, 2, 1)).toEqual(['a', 'c', 'b', 'd'])
  })

  it('moves an element down one slot (down button: slot i+2)', () => {
    expect(moveToSlot(list, 1, 3)).toEqual(['a', 'c', 'b', 'd'])
  })

  it('moves an element to the front and to the end', () => {
    expect(moveToSlot(list, 3, 0)).toEqual(['d', 'a', 'b', 'c'])
    expect(moveToSlot(list, 0, list.length)).toEqual(['b', 'c', 'd', 'a'])
  })

  it('drops after block i at slot i+1: the element lands right below the target', () => {
    expect(moveToSlot(list, 0, 2)).toEqual(['b', 'a', 'c', 'd'])
  })

  it('is a no-op (same array) when the slot would not move the element', () => {
    // drop top-half onto itself: slot === from
    expect(moveToSlot(list, 1, 1)).toBe(list)
    // drop bottom-half onto itself: slot === from + 1
    expect(moveToSlot(list, 1, 2)).toBe(list)
    // up button on the first block / down on the last: clamped into a no-op
    expect(moveToSlot(list, 0, -1)).toBe(list)
    expect(moveToSlot(list, 3, 5)).toBe(list)
  })

  it('clamps out-of-range slots', () => {
    expect(moveToSlot(list, 0, 99)).toEqual(['b', 'c', 'd', 'a'])
  })

  it('never mutates the input list', () => {
    moveToSlot(list, 0, 3)
    expect(list).toEqual(['a', 'b', 'c', 'd'])
  })
})

describe('hasDigestInput', () => {
  it('is false for a fresh draft and blank-only text blocks', () => {
    expect(hasDigestInput([newTextPart()])).toBe(false)
    expect(hasDigestInput([text('  '), text('\n\t')])).toBe(false)
  })

  it('is true for any non-blank text or an image', () => {
    expect(hasDigestInput([text(' x ')]), 'non-blank text').toBe(true)
    expect(hasDigestInput([image('data:image/png;base64,AA==')]), 'image').toBe(true)
  })

  it('counts a draft image whose data URL does not parse as nothing', () => {
    // the same conversion the submit sends: the button must never be
    // enabled for a request the submit would silently no-op
    expect(hasDigestInput([image('not-a-data-url')])).toBe(false)
    expect(hasDigestInput([image('not-a-data-url'), text('hi')])).toBe(true)
  })
})

describe('wireDigestParts', () => {
  it('drops blank text blocks and keeps non-blank text verbatim', () => {
    const parts: DigestDraftPart[] = [newTextPart(), text('  keep my  spacing  '), text('   ')]
    expect(wireDigestParts(parts)).toEqual([{ type: 'text', text: '  keep my  spacing  ' }])
  })

  it('converts image drafts into attachment parts, preserving the interleaving', () => {
    const parts: DigestDraftPart[] = [text('before'), image('data:image/png;base64,aGVsbG8='), text('after')]
    expect(wireDigestParts(parts)).toEqual([
      { type: 'text', text: 'before' },
      { type: 'attachment', kind: 'image', mimeType: 'image/png', content: { type: 'base64', base64: 'aGVsbG8=' } },
      { type: 'text', text: 'after' },
    ])
  })

  it('strips whitespace from folded base64 payloads', () => {
    const parts: DigestDraftPart[] = [image('data:image/jpeg;base64,aa bb\ncd ')]
    expect(wireDigestParts(parts)).toEqual([
      {
        type: 'attachment',
        kind: 'image',
        mimeType: 'image/jpeg',
        content: { type: 'base64', base64: 'aabbcd' },
      },
    ])
  })

  it('skips a draft image whose data URL does not parse', () => {
    expect(wireDigestParts([image('not-a-data-url'), image('data:text/plain;base64,aGk=')])).toEqual([])
  })

  it('answers an empty list for an all-blank draft', () => {
    expect(wireDigestParts([newTextPart()])).toEqual([])
  })
})
