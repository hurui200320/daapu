import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** Human-readable message of an unknown error (drops the "Error: " prefix). */
export function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
