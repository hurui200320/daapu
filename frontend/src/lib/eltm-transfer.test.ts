import { describe, expect, it } from 'vitest'
import { parseEltmImportFile } from './eltm-transfer'

const validPayload = {
  entities: {
    'uuid-a': {
      name: 'kindle',
      category: 'device',
      attributes: { model: 'k4' },
      notes: [{ date: '2026-08-17', note: 'bought it' }],
    },
  },
  relationships: [{ srcUuid: 'uuid-a', verb: 'works_with', dstUuid: 'uuid-a', valid: true, notes: [] }],
}

describe('parseEltmImportFile', () => {
  it('parses an exported ELTM file', async () => {
    const file = new File([JSON.stringify(validPayload)], 'eltm.json')
    expect(await parseEltmImportFile(file)).toEqual(validPayload)
  })

  it('parses an empty export', async () => {
    const file = new File([JSON.stringify({ entities: {}, relationships: [] })], 'empty.json')
    expect(await parseEltmImportFile(file)).toEqual({ entities: {}, relationships: [] })
  })

  it('rejects a non-JSON file with the file name in the error', async () => {
    const file = new File(['not json {'], 'broken.json')
    await expect(parseEltmImportFile(file)).rejects.toThrow('"broken.json" is not valid JSON')
  })

  it('rejects a payload without the two top-level parts', async () => {
    const wrongObject = new File([JSON.stringify({ entities: [] })], 'w1.json')
    await expect(parseEltmImportFile(wrongObject)).rejects.toThrow('not an exported ELTM file')
    const primitive = new File(['"just a string"'], 'w2.json')
    await expect(parseEltmImportFile(primitive)).rejects.toThrow('not an exported ELTM file')
    // entities as an ARRAY is not the payload shape either (it is an object
    // keyed by uuid)
    const arrayEntities = new File([JSON.stringify({ entities: [], relationships: [] })], 'w3.json')
    await expect(parseEltmImportFile(arrayEntities)).rejects.toThrow('not an exported ELTM file')
  })

  it('rejects an entity entry with a missing or wrongly-typed field', async () => {
    const missingNotes = new File(
      [JSON.stringify({ entities: { a: { name: 'x', category: 'y', attributes: {} } }, relationships: [] })],
      'w4.json',
    )
    await expect(parseEltmImportFile(missingNotes)).rejects.toThrow('not an exported ELTM file')
    const attrNotStrings = new File(
      [
        JSON.stringify({
          entities: { a: { name: 'x', category: 'y', attributes: { k: 1 }, notes: [] }, relationships: [] },
        }),
      ],
      'w5.json',
    )
    await expect(parseEltmImportFile(attrNotStrings)).rejects.toThrow('not an exported ELTM file')
    const noteWrongShape = new File(
      [
        JSON.stringify({
          entities: {
            a: { name: 'x', category: 'y', attributes: {}, notes: [{ date: '2026-08-17' }] },
          },
          relationships: [],
        }),
      ],
      'w6.json',
    )
    await expect(parseEltmImportFile(noteWrongShape)).rejects.toThrow('not an exported ELTM file')
  })

  it('rejects a relationship entry with a missing or wrongly-typed field', async () => {
    const missingValid = new File(
      [
        JSON.stringify({
          entities: {},
          relationships: [{ srcUuid: 'a', verb: 'knows', dstUuid: 'b', notes: [] }],
        }),
      ],
      'w7.json',
    )
    await expect(parseEltmImportFile(missingValid)).rejects.toThrow('not an exported ELTM file')
    const validNotBoolean = new File(
      [
        JSON.stringify({
          entities: {},
          relationships: [{ srcUuid: 'a', verb: 'knows', dstUuid: 'b', valid: 'yes', notes: [] }],
        }),
      ],
      'w8.json',
    )
    await expect(parseEltmImportFile(validNotBoolean)).rejects.toThrow('not an exported ELTM file')
  })
})
