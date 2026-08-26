import { errMsg } from './utils'

export type ToastKind = 'info' | 'error'

/**
 * Global transient notifications, rendered as a fixed top-right stack in
 * App.svelte. Action errors from anywhere (sidebar CRUD, chat load, send
 * failures) surface here via [pushError]; informational nudges ('Select a
 * chat first') via [push]. Contextual errors that stay tied to their view
 * (the chat view's run-error banner, the ELTM view's inline error) are
 * intentionally NOT routed through this store.
 */
class ToastStore {
  toasts = $state<{ id: number; message: string; kind: ToastKind }[]>([])
  private nextId = 0

  push(message: string, kind: ToastKind = 'info', timeoutMs = 5000) {
    const id = this.nextId++
    this.toasts = [...this.toasts, { id, message, kind }]
    setTimeout(() => this.dismiss(id), timeoutMs)
  }

  /** Push a caught error's message (any Error shape, no "Error: " prefix). */
  pushError(e: unknown, timeoutMs = 5000) {
    this.push(errMsg(e), 'error', timeoutMs)
  }

  dismiss(id: number) {
    this.toasts = this.toasts.filter((t) => t.id !== id)
  }
}

export const toastStore = new ToastStore()
