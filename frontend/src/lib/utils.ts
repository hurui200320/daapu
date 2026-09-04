import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** Human-readable message of an unknown error (drops the "Error: " prefix). */
export function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/**
 * Deep equality via JSON dump, for the "replace only when it changed" resync
 * gates (chat list, model catalog, personas, ELTM rows). Key-order sensitive
 * by design: both sides always come from the same backend JSON, so key order
 * matches; the payloads never carry undefined/NaN, whose stringify would lie.
 */
export function jsonEquals(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

/**
 * Trigger a browser download of `text` as `filename`: a JSON blob on a
 * programmatic anchor click, the URL revoked right after (the click hands
 * the blob to the download synchronously). Shared by the chat and persona
 * exports — the file naming schemes are owned by the backend's export
 * routes (ChatsRoute.kt / PersonasRoute.kt).
 */
export function downloadJsonFile(filename: string, text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}
