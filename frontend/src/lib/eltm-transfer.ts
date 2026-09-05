import type { EltmExportEntity, EltmExportNote, EltmExportPayload, EltmExportRelationship } from './types'

/**
 * Parse an exported ELTM file into the import payload with a minimal shape
 * check: the server owns the deep validation (dates parse, endpoints
 * resolve, values are single lines — see `EltmTransferService.importEltm`),
 * this only rejects obviously-wrong files before a request is spent, with
 * the file name in every error message.
 */
export async function parseEltmImportFile(file: File): Promise<EltmExportPayload> {
  let parsed: unknown
  try {
    parsed = JSON.parse(await file.text())
  } catch {
    throw new Error(`"${file.name}" is not valid JSON`)
  }
  const candidate = parsed as Partial<EltmExportPayload> | null
  if (
    !candidate ||
    typeof candidate.entities !== 'object' ||
    candidate.entities === null ||
    Array.isArray(candidate.entities) ||
    !Array.isArray(candidate.relationships)
  ) {
    throw new Error(
      `"${file.name}" is not an exported ELTM file (expected {"entities": {...}, "relationships": [...]})`,
    )
  }
  for (const [uuid, entity] of Object.entries(candidate.entities as Record<string, unknown>)) {
    if (!isEltmExportEntity(entity)) {
      throw new Error(
        `"${file.name}" is not an exported ELTM file (entity "${uuid}" must carry name, category, attributes and notes)`,
      )
    }
  }
  for (const relationship of candidate.relationships as unknown[]) {
    if (!isEltmExportRelationship(relationship)) {
      throw new Error(
        `"${file.name}" is not an exported ELTM file (each relationship must carry srcUuid, verb, dstUuid, valid and notes)`,
      )
    }
  }
  return candidate as EltmExportPayload
}

function isEltmExportEntity(candidate: unknown): candidate is EltmExportEntity {
  const entity = candidate as Partial<EltmExportEntity> | null
  return (
    !!entity &&
    typeof entity.name === 'string' &&
    typeof entity.category === 'string' &&
    isStringRecord(entity.attributes) &&
    Array.isArray(entity.notes) &&
    entity.notes.every(isEltmExportNote)
  )
}

function isEltmExportRelationship(candidate: unknown): candidate is EltmExportRelationship {
  const relationship = candidate as Partial<EltmExportRelationship> | null
  return (
    !!relationship &&
    typeof relationship.srcUuid === 'string' &&
    typeof relationship.verb === 'string' &&
    typeof relationship.dstUuid === 'string' &&
    typeof relationship.valid === 'boolean' &&
    Array.isArray(relationship.notes) &&
    relationship.notes.every(isEltmExportNote)
  )
}

function isEltmExportNote(candidate: unknown): candidate is EltmExportNote {
  const note = candidate as Partial<EltmExportNote> | null
  return !!note && typeof note.date === 'string' && typeof note.note === 'string'
}

function isStringRecord(candidate: unknown): candidate is Record<string, string> {
  if (typeof candidate !== 'object' || candidate === null || Array.isArray(candidate)) return false
  return Object.values(candidate).every((value) => typeof value === 'string')
}
