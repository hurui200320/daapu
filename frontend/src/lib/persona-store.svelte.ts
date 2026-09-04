import {
  createPersona,
  deletePersona,
  exportPersonas as apiExportPersonas,
  importPersonas as apiImportPersonas,
  listPersonas,
  updatePersona,
} from './api'
import type { Persona } from './types'
import { toastStore } from './toast-store.svelte'
import { onIntervalAndFocus } from './resync'
import { downloadJsonFile, jsonEquals } from './utils'
import { parsePersonaImportFile } from './persona-transfer'

/**
 * The persona catalog (the code default + the `personas` rows), shared by
 * the composer's persona picker and the `#/personas` management view.
 * Resynced on the same 30s/focus cadence as the chat list: personas are
 * editable from any browser tab.
 */
class PersonaStore {
  personas = $state<Persona[]>([])

  // busy flags of the file actions (export/import), read by the view's
  // buttons — same pattern as the chat store's importingChats/exportingAll
  exporting = $state(false)
  importing = $state(false)

  private started = false

  async init() {
    if (this.started) return
    this.started = true
    await this.resync()
    onIntervalAndFocus(30_000, () => void this.resync())
  }

  /** Silent refresh; replaces the list only when it changed. */
  private async resync() {
    try {
      const fresh = await listPersonas()
      if (!jsonEquals(fresh, this.personas)) {
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
      toastStore.pushError(e)
      return false
    }
  }

  async update(id: number, name: string, systemPrompt: string, allowedNamespaces: string[]): Promise<boolean> {
    try {
      const persona = await updatePersona(id, { name, systemPrompt, allowedNamespaces })
      this.personas = this.personas.map((p) => (p.id === id ? persona : p))
      return true
    } catch (e) {
      toastStore.pushError(e)
      return false
    }
  }

  async delete(id: number): Promise<void> {
    try {
      await deletePersona(id)
      this.personas = this.personas.filter((p) => p.id !== id)
    } catch (e) {
      toastStore.pushError(e)
    }
  }

  /**
   * Export every persona row as one `personas.json` download. Failures
   * surface as a toast.
   */
  async exportAll(): Promise<void> {
    if (this.exporting) return
    this.exporting = true
    try {
      const entries = await apiExportPersonas()
      // the name is hardcoded because fetch does not act on the backend's
      // Content-Disposition — keep it in sync with the attachment of the
      // export route (PersonasRoute.kt), like the chat store does with
      // ChatsRoute.kt
      downloadJsonFile('personas.json', JSON.stringify(entries))
    } catch (e) {
      toastStore.pushError(e)
    } finally {
      this.exporting = false
    }
  }

  /**
   * Import one exported personas file (parsed in `persona-transfer.ts`,
   * matched and created server-side — see `api.ts` `importPersonas`):
   * entries matching an existing persona are skipped, the rest are created.
   * The catalog resyncs regardless of the outcome — the server's import is
   * fail-fast partial, so a 400 can still carry earlier creates. On success
   * a toast reports the created/skipped split; parse/shape errors and the
   * server's validation errors surface as toasts (with the file name).
   */
  async importFile(file: File): Promise<void> {
    if (this.importing) return
    this.importing = true
    try {
      const entries = await parsePersonaImportFile(file)
      const summary = await apiImportPersonas(entries)
      toastStore.push(`Personas imported: ${summary.created.length} created, ${summary.skipped.length} skipped`)
    } catch (e) {
      toastStore.pushError(e)
    } finally {
      await this.resync()
      this.importing = false
    }
  }
}

export const personaStore = new PersonaStore()
