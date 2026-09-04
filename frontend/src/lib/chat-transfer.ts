import type { ChatExport } from './types'

/**
 * Trigger a browser download of `text` as `filename`: a JSON blob on a
 * programmatic anchor click, the URL revoked right after (the click hands
 * the blob to the download synchronously). Used by the chat export — the
 * file naming scheme is owned by the backend's export route (ChatsRoute.kt).
 */
export function downloadJsonFile(filename: string, text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

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
