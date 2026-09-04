import { describe, expect, it } from 'vitest'
import { parsePersonaImportFile } from './persona-transfer'

describe('parsePersonaImportFile', () => {
  it('parses an exported personas file', async () => {
    const file = new File(
      [JSON.stringify([{ name: 'Writer', systemPrompt: 'You are a writer.', allowedNamespaces: ['gsg'] }])],
      'personas.json',
    )
    expect(await parsePersonaImportFile(file)).toEqual([
      { name: 'Writer', systemPrompt: 'You are a writer.', allowedNamespaces: ['gsg'] },
    ])
  })

  it('parses an empty export', async () => {
    const file = new File(['[]'], 'empty.json')
    expect(await parsePersonaImportFile(file)).toEqual([])
  })

  it('rejects a non-JSON file with the file name in the error', async () => {
    const file = new File(['not json {'], 'broken.json')
    await expect(parsePersonaImportFile(file)).rejects.toThrow('"broken.json" is not valid JSON')
  })

  it('rejects a non-array payload', async () => {
    const wrongObject = new File([JSON.stringify({ Writer: { systemPrompt: 'p', allowedNamespaces: [] } })], 'w1.json')
    await expect(parsePersonaImportFile(wrongObject)).rejects.toThrow('not an exported personas file')
    // JSON.parse accepts primitives: those are not payloads either
    const primitive = new File(['"just a string"'], 'w2.json')
    await expect(parsePersonaImportFile(primitive)).rejects.toThrow('not an exported personas file')
  })

  it('rejects an entry with a missing or wrongly-typed field', async () => {
    const missingField = new File([JSON.stringify([{ name: 'W', systemPrompt: 'p' }])], 'w3.json')
    await expect(parsePersonaImportFile(missingField)).rejects.toThrow('not an exported personas file')
    const nsNotArray = new File(
      [JSON.stringify([{ name: 'W', systemPrompt: 'p', allowedNamespaces: 'gsg' }])],
      'w4.json',
    )
    await expect(parsePersonaImportFile(nsNotArray)).rejects.toThrow('not an exported personas file')
    const nsNotStrings = new File(
      [JSON.stringify([{ name: 'W', systemPrompt: 'p', allowedNamespaces: [1] }])],
      'w5.json',
    )
    await expect(parsePersonaImportFile(nsNotStrings)).rejects.toThrow('not an exported personas file')
    const entryPrimitive = new File([JSON.stringify(['Writer'])], 'w6.json')
    await expect(parsePersonaImportFile(entryPrimitive)).rejects.toThrow('not an exported personas file')
  })
})
