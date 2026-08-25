import { createPersona, deletePersona, listPersonas, updatePersona } from './api'
import type { Persona } from './types'
import { toastStore } from './toast-store.svelte'

/**
 * The persona catalog (the code default + the `personas` rows), shared by
 * the composer's persona picker and the `#/personas` management view.
 * Resynced on the same 30s/focus cadence as the chat list: personas are
 * editable from any browser tab.
 */
class PersonaStore {
  personas = $state<Persona[]>([])

  private started = false

  async init() {
    if (this.started) return
    this.started = true
    await this.resync()
    setInterval(() => void this.resync(), 30_000)
    window.addEventListener('focus', () => void this.resync())
  }

  /** Silent refresh; replaces the list only when it changed. */
  private async resync() {
    try {
      const fresh = await listPersonas()
      if (JSON.stringify(fresh) !== JSON.stringify(this.personas)) {
        this.personas = fresh
      }
    } catch {
      // transient backend hiccup: keep the current list
    }
  }

  async create(name: string, systemPrompt: string, allowedNamespaces: string[]): Promise<boolean> {
    try {
      const persona = await createPersona({ name, systemPrompt, allowedNamespaces })
      this.personas = [...this.personas, persona]
      return true
    } catch (e) {
      toastStore.push(String(e))
      return false
    }
  }

  async update(
    id: number,
    name: string,
    systemPrompt: string,
    allowedNamespaces: string[],
  ): Promise<boolean> {
    try {
      const persona = await updatePersona(id, { name, systemPrompt, allowedNamespaces })
      this.personas = this.personas.map((p) => (p.id === id ? persona : p))
      return true
    } catch (e) {
      toastStore.push(String(e))
      return false
    }
  }

  async delete(id: number): Promise<void> {
    try {
      await deletePersona(id)
      this.personas = this.personas.filter((p) => p.id !== id)
    } catch (e) {
      toastStore.push(String(e))
    }
  }
}

export const personaStore = new PersonaStore()
