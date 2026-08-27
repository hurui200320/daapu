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
