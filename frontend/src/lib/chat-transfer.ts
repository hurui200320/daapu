import type { ChatExport } from './types'

/**
 * Parse an exported chat file into the import payload with a minimal shape
 * check (`title` string + `messages` array): the server owns the deep
 * stored-chat validation (see `api.ts` `importChat`), this only rejects
 * obviously-wrong files before a request is spent, with the file name in
 * every error message.
 */
export async function parseChatExportFile(file: File): Promise<ChatExport> {
  let parsed: unknown
  try {
    parsed = JSON.parse(await file.text())
  } catch {
    throw new Error(`"${file.name}" is not valid JSON`)
  }
  const candidate = parsed as Partial<ChatExport> | null
  if (!candidate || typeof candidate.title !== 'string' || !Array.isArray(candidate.messages)) {
    throw new Error(`"${file.name}" is not an exported chat file (expected {"title", "messages"})`)
  }
  return { title: candidate.title, messages: candidate.messages }
}
