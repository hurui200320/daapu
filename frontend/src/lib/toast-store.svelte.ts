/**
 * Global transient error notifications, rendered as a fixed top-right stack
 * in App.svelte. Action errors from anywhere (sidebar CRUD, chat load, send
 * failures) surface here; contextual errors that stay tied to their view
 * (the chat view's run-error banner, the SSTM view's inline error) are
 * intentionally NOT routed through this store.
 */
class ToastStore {
  toasts = $state<{ id: number; message: string }[]>([])
  private nextId = 0

  push(message: string, timeoutMs = 5000) {
    const id = this.nextId++
    this.toasts = [...this.toasts, { id, message }]
    setTimeout(() => this.dismiss(id), timeoutMs)
  }

  dismiss(id: number) {
    this.toasts = this.toasts.filter((t) => t.id !== id)
  }
}

export const toastStore = new ToastStore()
