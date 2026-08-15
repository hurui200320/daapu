import {
  deleteChat,
  forkChat as apiForkChat,
  generateTitle,
  listChats,
  listModels,
  loadChat,
  newChat,
  renameChat,
  streamChat,
  truncateMessages as apiTruncateMessages,
} from './api'
import type { ChatInfo, ChatMessage, ChatMessagePart, ChatToolResultPart, ModelInfo } from './types'
import { toastStore } from './toast-store.svelte'

// mirrors the backend's data-URL handling (ChatRunService.parseImagePart:
// trims the URL and strips whitespace from the base64 payload)
const DATA_URL_RE = /^data:(image\/[a-zA-Z0-9.+-]+);base64,([\s\S]+)$/

// mirrors the backend's DEFAULT_CHAT_TITLE (db/Tables.kt)
const DEFAULT_CHAT_TITLE = 'New chat'

const STORAGE_KEY = 'daapu.model'

/**
 * All chat state + actions, shared by the sidebar (chat list CRUD), the chat
 * view (streaming) and the composer (model picker). The SSE event semantics
 * of the run loop are preserved verbatim from the previous ChatView
 * implementation: tool-round commits, retry wipes, DB resync on
 * done/error/abnormal close, optimistic user message.
 *
 * The model picker's localStorage persistence lives in App.svelte: the store
 * is a module-scope singleton, and `$effect` runes are only valid inside a
 * component.
 */
class ChatStore {
  chatId = $state('')
  knownChats = $state<ChatInfo[]>([])
  models = $state<ModelInfo[]>([])
  messages = $state<ChatMessage[]>([])

  // the per-message model picker lives in the Composer; the state is lifted
  // here so the usage indicator can show the context of the model that will
  // process the next message
  selectedModel = $state('')

  streaming = $state(false)
  streamReasoning = $state('')
  streamText = $state('')
  // tool calls of the round currently being streamed (uncommitted: wiped on retry)
  streamToolCalls = $state<{ name: string; args: Record<string, unknown> }[]>([])
  retrying = $state(false)
  // the current run's failure, shown in the chat view (contextual, not a
  // toast: it stays tied to the messages it relates to)
  streamError = $state<string | null>(null)

  // in-flight deletions per chat id: while a delete request is running (the
  // backend extracts SSTM from the history first, which can take minutes),
  // the chat is read-only — no rename/title/delete/send — until the backend
  // confirms the row is gone or reports an error
  deletingIds = $state<Set<string>>(new Set())

  // fork requests in flight per chat id: forks have no confirmation dialog
  // (unlike truncation) — the click starts the request immediately, and the
  // action buttons stay disabled until it settles, so a double-click cannot
  // create duplicate fork chats; other chats' buttons stay live
  forkingIds = $state<Set<string>>(new Set())

  private started = false

  /**
   * One-time startup: load the catalog + chat list, then start the background
   * chat-list resync (another session may create/rename/delete chats; titles
   * only arrive here via a refetch).
   */
  async init() {
    if (this.started) return
    this.started = true
    try {
      this.models = await listModels()
      this.knownChats = await listChats()
    } catch (e) {
      toastStore.push(String(e))
    }
    setInterval(() => void this.resyncChats(), 30_000)
    window.addEventListener('focus', () => void this.resyncChats())
  }

  /**
   * Refresh the chat list + model catalog from the DB. Replaces each list
   * only when it actually changed, so an open rename dialog keeps its target
   * while the rest of the list updates. Failures are silent: the next tick
   * retries (also covers a failed initial load — the picker must not stay
   * blank forever because the backend was briefly down at startup). A stale
   * selected model (catalog changed, different backend) falls back to the
   * first model, so a send never 400s on an id that no longer exists.
   */
  private async resyncChats() {
    try {
      const [freshChats, freshModels] = await Promise.all([listChats(), listModels()])
      if (JSON.stringify(freshChats) !== JSON.stringify(this.knownChats)) {
        this.knownChats = freshChats
      }
      if (JSON.stringify(freshModels) !== JSON.stringify(this.models)) {
        this.models = freshModels
        if (this.selectedModel && !freshModels.some((m) => m.id === this.selectedModel)) {
          this.selectedModel = freshModels[0]?.id ?? ''
        }
      }
    } catch {
      // transient backend hiccup: keep the current lists
    }
  }

  /**
   * Latest-round usage: scan backwards for the last assistant message that
   * carries token usage (each round's total = full prompt + output of that
   * round, the best proxy for current context occupancy). The context window
   * is always the currently selected model's, since the next message will be
   * processed by it. Missing either side (no usage data, unknown/null
   * contextLength) hides the indicator.
   */
  private computeUsage(): { used: number | null; context: number | null } {
    for (let i = this.messages.length - 1; i >= 0; i--) {
      const msg = this.messages[i]
      const used = msg.role === 'assistant' ? msg.meta?.totalTokens : null
      if (used == null) continue
      const model = this.models.find((m) => m.id === this.selectedModel)
      return { used, context: model?.contextLength ?? null }
    }
    return { used: null, context: null }
  }

  usage = $derived(this.computeUsage())

  /** Mirror of the backend's ChatMessagePart.Attachment, for display only. */
  private dataUrlToPart(dataUrl: string): ChatMessagePart | null {
    const match = DATA_URL_RE.exec(dataUrl.trim())
    if (!match) return null
    return {
      type: 'attachment',
      kind: 'image',
      mimeType: match[1],
      content: { type: 'base64', base64: match[2].replace(/\s/g, '') },
    }
  }

  /**
   * Sync the view with whatever the DB actually holds. A failed reload must
   * not mask the run's own error, so keep the current display on failure.
   */
  private async reloadFromDb(id: string) {
    try {
      this.messages = await loadChat(id)
    } catch {
      // keep the optimistic display; nothing better to show
    }
  }

  async loadMessages(id: string) {
    this.streamError = null
    try {
      this.messages = await loadChat(id)
    } catch (e) {
      toastStore.push(String(e))
    }
  }

  pickChat(id: string) {
    this.chatId = id
    void this.loadMessages(id)
  }

  async createNewChat(): Promise<void> {
    try {
      this.chatId = await newChat()
      // prepend to match the server's newest-first order
      this.knownChats = [{ id: this.chatId, title: DEFAULT_CHAT_TITLE }, ...this.knownChats]
      this.messages = []
    } catch (e) {
      toastStore.push(String(e))
    }
  }

  async renameChat(id: string, title: string): Promise<void> {
    if (this.deletingIds.has(id)) return
    try {
      await renameChat(id, title)
      this.knownChats = this.knownChats.map((c) => (c.id === id ? { ...c, title } : c))
    } catch (e) {
      toastStore.push(String(e))
    }
  }

  /**
   * Ask the server to generate a session title from the chat's history and
   * update the chat list with the returned title. Failures surface as a
   * global toast; the caller keeps its own "in progress" flag around the
   * call.
   */
  async generateTitle(id: string): Promise<void> {
    if (this.deletingIds.has(id)) return
    try {
      const chat = await generateTitle(id)
      this.knownChats = this.knownChats.map((c) => (c.id === id ? { ...c, title: chat.title } : c))
    } catch (e) {
      toastStore.push(String(e))
    }
  }

  /**
   * Delete a chat: the backend extracts SSTM from its history before removing
   * the row, which can take minutes. While the request is in flight the chat
   * is read-only ([deletingIds]) — a second delete call is a no-op. On
   * failure (e.g. the extraction failed and the row is kept) the lock is
   * released and the error surfaces as a toast, so the user can retry.
   */
  async deleteChat(id: string): Promise<void> {
    if (this.deletingIds.has(id)) return
    this.deletingIds.add(id)
    try {
      await deleteChat(id)
      this.knownChats = this.knownChats.filter((c) => c.id !== id)
      if (this.chatId === id) {
        this.chatId = ''
        this.messages = []
      }
    } catch (e) {
      toastStore.push(String(e))
    } finally {
      this.deletingIds.delete(id)
    }
  }

  /**
   * Drop the message at `index` (a user message) and everything after it,
   * WITHOUT SSTM extraction (a typo'd turn must not leak into memories).
   * `chatId` is pinned by the caller (the confirmation dialog captured it at
   * open time), so a chat switch before the request cannot redirect the
   * delete; the slice applies only while still on the same chat. The backend
   * rejects (409) while a run is active; the UI hides the button while
   * streaming anyway, so the local slice mirrors the DB exactly.
   */
  async truncateMessages(chatId: string, index: number): Promise<void> {
    if (!chatId || this.deletingIds.has(chatId)) return
    try {
      await apiTruncateMessages(chatId, index)
      if (this.chatId === chatId) {
        this.messages = this.messages.slice(0, index)
        // the failed turn is gone, so the run-failure banner goes with it
        this.streamError = null
      }
    } catch (e) {
      toastStore.push(String(e))
    }
  }

  /**
   * Fork: copy the history up to and including the message at `index` (an
   * assistant message that ended naturally) into a new chat and switch to it.
   * The new chat starts as "New chat" with empty sstm state (its first run
   * flags `sstm-updated`), so the original chat stays untouched. The switch
   * happens only while still on the source chat: a chat switch during the
   * request must not hijack the view (the fork chat is still added to the
   * list either way).
   */
  async forkChat(index: number): Promise<void> {
    const id = this.chatId.trim()
    if (!id || this.deletingIds.has(id) || this.forkingIds.has(id)) return
    this.forkingIds.add(id)
    try {
      const chat = await apiForkChat(id, index)
      this.knownChats = [chat, ...this.knownChats]
      if (this.chatId === id) {
        this.chatId = chat.id
        this.messages = this.messages.slice(0, index + 1)
        this.streamError = null
      }
    } catch (e) {
      toastStore.push(String(e))
    } finally {
      this.forkingIds.delete(id)
    }
  }

  async send(text: string, images: { dataUrl: string }[], model: string): Promise<boolean> {
    const id = this.chatId.trim()
    if (!id) {
      toastStore.push('Select a chat first')
      return false
    }
    if (this.deletingIds.has(id)) {
      toastStore.push('This chat is being deleted')
      return false
    }
    this.streamError = null
    this.retrying = false
    this.streamReasoning = ''
    this.streamText = ''
    this.streamToolCalls = []
    this.streaming = true
    // optimistic: show the sent message right away; the final reload replaces
    // it with the stored form (and a failed run never reaches the store, so
    // the error/connection-closed reloads below drop it again)
    const userParts: ChatMessagePart[] = []
    if (text) userParts.push({ type: 'text', text })
    for (const image of images) {
      const part = this.dataUrlToPart(image.dataUrl)
      if (part) userParts.push(part)
    }
    this.messages = [...this.messages, { role: 'user', parts: userParts }]
    let failed = false
    let sawDone = false
    try {
      for await (const ev of streamChat(id, { text, images, model })) {
        switch (ev.event) {
          case 'reasoning':
            this.retrying = false
            this.streamReasoning += JSON.parse(ev.data).delta
            break
          case 'text':
            this.retrying = false
            this.streamText += JSON.parse(ev.data).delta
            break
          case 'tool_call':
            this.retrying = false
            this.streamToolCalls = [...this.streamToolCalls, JSON.parse(ev.data)]
            break
          case 'tool_result':
            // a tool result commits the current round: its assistant message
            // (reasoning + text + tool calls) is finished, so move it into the
            // history and reset the buffers — the next round must stream as
            // its own message, or its reasoning would append to this round's.
            // The backend emits one event per result of a round (parallel tool
            // calls => several), so only the FIRST event of a batch commits a
            // fresh pair; later ones append their part to the just-committed
            // tool_result message, mirroring the stored format. Display-only
            // state (the tool_call parts have no real id — the SSE event
            // doesn't carry one): the `done` reload replaces it with the
            // stored form.
            {
              const result = JSON.parse(ev.data)
              const resultPart: ChatToolResultPart = {
                type: 'tool_result',
                id: result.id,
                tool: result.name,
                isError: result.isError,
                parts: [{ type: 'text', text: result.content }],
              }
              const last = this.messages[this.messages.length - 1]
              if (this.streamToolCalls.length === 0 && last?.role === 'tool_result') {
                // 2nd..Nth result of the same batch: extend the committed
                // tool_result message instead of creating an empty pair
                this.messages = [
                  ...this.messages.slice(0, -1),
                  { ...last, parts: [...last.parts, resultPart] },
                ]
              } else {
                const parts: ChatMessagePart[] = []
                if (this.streamReasoning) parts.push({ type: 'reasoning', content: this.streamReasoning })
                if (this.streamText) parts.push({ type: 'text', text: this.streamText })
                for (const call of this.streamToolCalls) {
                  parts.push({ type: 'tool_call', id: '', tool: call.name, args: call.args })
                }
                this.messages = [
                  ...this.messages,
                  { role: 'assistant', parts },
                  { role: 'tool_result', parts: [resultPart] },
                ]
                this.streamReasoning = ''
                this.streamText = ''
                this.streamToolCalls = []
              }
            }
            break
          case 'retry':
            // the server re-streams the whole round from scratch; drop the
            // failed round's partials so the retried output doesn't append to
            // stale text (committed rounds live in `messages` and survive)
            this.retrying = true
            this.streamReasoning = ''
            this.streamText = ''
            this.streamToolCalls = []
            break
          case 'done':
            sawDone = true
            this.retrying = false
            // the server stores history only on success; reload it so the UI
            // always matches the DB (covers tool-call rounds too). Uses the
            // captured id, not the mutable input state.
            await this.reloadFromDb(id)
            break
          case 'error':
            failed = true
            this.retrying = false
            this.streamError = JSON.parse(ev.data).message ?? ev.data
            // the run failed before storing, so the optimistic user message
            // and any committed tool rounds are not in the DB: reload to match
            await this.reloadFromDb(id)
            break
        }
      }
      if (!sawDone && !failed) {
        // the connection closed cleanly without a terminal event (e.g. the
        // server restarted mid-run): the run's outcome is unknown, so sync
        // with whatever the DB actually has and restore the draft
        failed = true
        await this.reloadFromDb(id)
        this.streamError = 'connection closed before the run completed'
      }
    } catch (e) {
      failed = true
      toastStore.push(String(e))
      // a fetch/parse failure may still have stored the run server-side (or
      // not): sync with the DB so a phantom optimistic message never sticks
      await this.reloadFromDb(id)
    } finally {
      this.streaming = false
    }
    return !failed
  }
}

export const chatStore = new ChatStore()

/**
 * The model picker's persistence: restore the stored id once the catalog is
 * loaded (a stale id would render a blank picker and a confusing 400 on
 * send — fall back to the first model), and write every change back. Lives
 * in App.svelte via $effect because the store itself is module-scoped.
 */
export const MODEL_STORAGE_KEY = STORAGE_KEY
