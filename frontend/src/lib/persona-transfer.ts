import type { PersonaExportEntry } from './types'

/**
 * Parse an exported personas file into the import payload with a minimal
 * shape check (an array of `{name, systemPrompt, allowedNamespaces}` objects
 * with the right primitive types): the server owns the deep validation (see
 * `api.ts` `importPersonas`), this only rejects obviously-wrong files before
 * a request is spent, with the file name in every error message.
 */
export async function parsePersonaImportFile(file: File): Promise<PersonaExportEntry[]> {
  let parsed: unknown
  try {
    parsed = JSON.parse(await file.text())
  } catch {
    throw new Error(`"${file.name}" is not valid JSON`)
  }
  if (!Array.isArray(parsed)) {
    throw new Error(`"${file.name}" is not an exported personas file (expected an array of persona entries)`)
  }
  for (const entry of parsed) {
    const candidate = entry as Partial<PersonaExportEntry> | null
    if (
      !candidate ||
      typeof candidate.name !== 'string' ||
      typeof candidate.systemPrompt !== 'string' ||
      !Array.isArray(candidate.allowedNamespaces) ||
      candidate.allowedNamespaces.some((ns) => typeof ns !== 'string')
    ) {
      throw new Error(
        `"${file.name}" is not an exported personas file (expected {"name", "systemPrompt", "allowedNamespaces"} entries)`,
      )
    }
  }
  return parsed
}
