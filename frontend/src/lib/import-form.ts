import type { EltmImportPart } from './api'
import { dataUrlToImagePart } from './display'

/**
 * Pure logic of the ELTM import form (`EltmView.svelte`'s Import tab):
 * the draft is an ordered list of text blocks and images, and everything
 * below derives from it without touching the DOM or reactivity, so it is
 * unit-tested here (src/lib/*.test.ts) and the component stays a thin
 * reactive host (see display.ts for the layering convention).
 */

/** A draft block in the import form: a text block or a picked/pasted image. */
export type ImportDraftPart = { kind: 'text'; text: string } | { kind: 'image'; dataUrl: string }

export function newTextPart(): ImportDraftPart {
  return { kind: 'text', text: '' }
}

/**
 * Whether the draft yields a non-empty import request: the SAME conversion
 * [wireImportParts] performs at submit time, so the Import button (gated by
 * this) is enabled exactly when the submit would send something — never for
 * a request the submit would silently no-op (a draft image whose data URL
 * does not parse counts as nothing; the form's own pipeline never produces
 * one).
 */
export function hasImportInput(parts: ImportDraftPart[]): boolean {
  return wireImportParts(parts).length > 0
}

/**
 * The wire parts for one import request: text blocks in order (blank ones
 * dropped — the server treats an all-blank request as empty), and images
 * converted from their draft data URLs into attachment parts (see
 * display.ts; the conversion cannot fail for the image data URLs the form
 * produces — a null would only skip a broken draft entry).
 */
export function wireImportParts(parts: ImportDraftPart[]): EltmImportPart[] {
  const wire: EltmImportPart[] = []
  for (const part of parts) {
    if (part.kind === 'text') {
      if (part.text.trim().length > 0) wire.push({ type: 'text', text: part.text })
    } else {
      const imagePart = dataUrlToImagePart(part.dataUrl)
      if (imagePart) wire.push(imagePart)
    }
  }
  return wire
}

/**
 * Move the element at [from] to the insertion slot [to] (0..list.length, in
 * the ORIGINAL list's coordinates — dropping after block i is slot i+1),
 * returning a new list. A slot that would not move the element (slot ===
 * from or from + 1, after clamping) returns the SAME list. Shared by the
 * drag drop handler (top half = slot i, bottom half = slot i+1) and the
 * touch-only move buttons (up = slot i-1, down = slot i+2).
 */
export function moveToSlot<T>(list: T[], from: number, to: number): T[] {
  const slot = Math.max(0, Math.min(list.length, to))
  if (slot === from || slot === from + 1) return list
  const next = [...list]
  const [moved] = next.splice(from, 1)
  next.splice(slot > from ? slot - 1 : slot, 0, moved)
  return next
}
