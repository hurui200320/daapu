import { SvelteSet } from 'svelte/reactivity'
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
import { DEFAULT_PERSONA_ID } from './types'
import { chatHomePath, chatPath, navigate, replaceRoute, router } from './router.svelte'
import { personaStore } from './persona-store.svelte'
import { onIntervalAndFocus } from './resync'
import { toastStore } from './toast-store.svelte'

// mirrors the backend's data-URL handling (ChatRunService.parseImagePart:
// trims the URL and strips whitespace from the base64 payload)
const DATA_URL_RE = /^data:(image\/[a-zA-Z0-9.+-]+);base64,([\s\S]+)$/

// mirrors the backend's DEFAULT_CHAT_TITLE (db/Tables.kt)
const DEFAULT_CHAT_TITLE = 'New chat'

// The model picker's persistence key. The restore/write-back lives in
// App.svelte via $effect: the store is a module-scope singleton, and `$effect`
// runes are only valid inside a component.
export const MODEL_STORAGE_KEY = 'daapu.model'

/**
 * Stable per-message identity for collapsible open-state tracking: role +
 * the tool calls (name + args, in order; ids ignored — the display commit
 * has none) + the joined text parts. Content-based because the `done` reload
 * replaces every message object wholesale and a mid-run compaction shifts
 * positions: the same round must keep its signature wherever it lands, and
 * a different round must never inherit another's. The text join is
 * coalescing-invariant (the display commit's single text part vs. the stored
 * form's provider blocks join to the same string). Approximate identity:
 * identical content is indistinguishable by design (and visually too).
 *
 * Tool_result messages all share the constant `role:tool_result` signature:
 * their collapsibles key on the unique result id instead (see
 * MessageItem.partOrdinalKey), so a shared bucket cannot mix up their
 * toggles — and only a shared bucket survives the display's batching (one
 * display message per round's results, one stored message per result).
 */
export function roundSignature(m: ChatMessage): string {
  if (m.role !== 'assistant') return `role:${m.role}`
  const calls = m.parts
    .filter((p): p is Extract<ChatMessagePart, { type: 'tool_call' }> => p.type === 'tool_call')
    .map((c) => [c.tool, c.args])
  const text = m.parts
    .filter((p): p is Extract<ChatMessagePart, { type: 'text' }> => p.type === 'text')
    .map((p) => p.text)
    .join('')
  return `assistant:${JSON.stringify(calls)}:${text}`
}

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
 *
 * Which chat is open is owned by the URL (router.svelte.ts): the App.svelte
 * route effect translates hash changes into pickChat/closeChat, and actions
 * that change the open chat (create/fork/delete) navigate the hash.
 */
class ChatStore {
  chatId = $state('')
  knownChats = $state<ChatInfo[]>([])
  models = $state<ModelInfo[]>([])
  messages = $state<ChatMessage[]>([])

  // true while the open chat's history is being fetched: the chat view shows
  // a loading placeholder instead of the empty state, so a chat with history
  // never flashes "No messages yet" during the load
  chatLoading = $state(false)

  // the user's own collapsible toggles per round, keyed by the round's
  // identity signature ([roundSignature] — tool calls + text): a toggle wins
  // over the closed default. Store-level (not MessageItem-local) so the
  // toggles follow the round across the done-reload — a compaction-shifted
  // message at the same position never inherits another round's states.
  // Cleared on chat switches only (NOT on send: toggles persist across runs,
  // matching the old per-instance behavior).
  partOverridesBySignature = $state<Record<string, Record<string, boolean>>>({})

  // the per-message model picker lives in the Composer; the state is lifted
  // here so the usage indicator can show the context of the model that will
  // process the next message
  selectedModel = $state('')

  // the per-chat persona: a TRANSIENT picker override (cleared on chat
  // switch) on top of the chat's recorded persona (knownChats' personaId,
  // stamped by the backend on every successful run). The request always
  // carries the effective id — the record is never the run's source. null =
  // no override (0 is a real persona id: the code default).
  personaOverride = $state<number | null>(null)

  // per-chat composer drafts (text + images): the composer is one
  // always-mounted component, so without these a draft typed in one chat
  // would leak into the next on a chat switch. '' (no chat open) is a valid
  // key. Session-only; entries are dropped with their chat on delete.
  drafts = $state<Record<string, { text: string; images: { dataUrl: string }[] }>>({})

  streaming = $state(false)
  // true once a terminal event (done/error) was received and the view is
  // resyncing with the DB: the live round's buffers are stale/empty, so the
  // live block must not render ("Processing…" shimmer after the run
  // completed, or the failed round's partials) — but `streaming` stays true
  // until the resync finishes so a pending route change cannot apply
  // mid-reload (reloadFromDb's unconditional assignment would then render
  // the wrong chat's messages under the new id)
  runEnding = $state(false)
  // the live round's collapsible open states (lifted from MessageList so the
  // display commit never snaps them mid-stream): a fresh round opens them,
  // the user's mid-stream collapse stays collapsed until the round ends (the
  // committed/stored blocks start closed unless the user opened them — see
  // [partOverridesBySignature])
  streamReasoningOpen = $state(true)
  streamToolCallsOpen = $state<boolean[]>([])
  streamReasoning = $state('')
  streamText = $state('')
  // tool calls of the round currently being streamed (uncommitted: wiped on retry)
  streamToolCalls = $state<{ name: string; args: Record<string, unknown> }[]>([])
  retrying = $state(false)
  // the current run's failure, shown in the chat view (contextual, not a
  // toast: it stays tied to the messages it relates to)
  streamError = $state<string | null>(null)

  // in-flight deletions per chat id: while a delete request is running (the
  // backend extracts memories from the history first, which can take minutes),
  // the chat is read-only — no rename/title/delete/send — until the backend
  // confirms the row is gone or reports an error
  // (SvelteSet: a native Set under $state is not proxied, so .add()/.delete()
  // would never invalidate the templates reading .has())
  deletingIds = new SvelteSet<string>()

  // chats deleted this session, per id: a route pointing at one (e.g. the
  // history entry left behind when the open chat was deleted from another
  // view) must not be picked — the load would 404; the App route effect
  // redirects such landings to home instead
  deletedChatIds = new SvelteSet<string>()

  // fork requests in flight per chat id: forks have no confirmation dialog
  // (unlike truncation) — the click starts the request immediately, and the
  // action buttons stay disabled until it settles, so a double-click cannot
  // create duplicate fork chats; other chats' buttons stay live
  forkingIds = new SvelteSet<string>()

  private started = false
  // true while a create request is in flight: a double-click on "New chat"
  // must not create two empty chats
  creatingChat = $state(false)

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
      toastStore.pushError(e)
    }
    // app-lifetime store: the disposer is intentionally ignored
    onIntervalAndFocus(30_000, () => void this.resyncChats())
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

  /**
   * The persona id the next send will carry: the transient picker override,
   * else the chat's recorded persona. A persona that no longer exists in the
   * loaded catalog (deleted here or in another tab) falls back to the other
   * source, then to the code default — the backend keeps stale records and
   * would 400 a stale id. An unloaded catalog (empty list) is still trusted:
   * a loaded catalog is never empty (the code default always leads it), and
   * the record was fetched together with the chats.
   */
  currentPersonaId = $derived(this.effectivePersonaId())

  private effectivePersonaId(): number {
    const exists = (id: number) =>
      personaStore.personas.length === 0 || personaStore.personas.some((p) => p.id === id)
    if (this.personaOverride != null && exists(this.personaOverride)) return this.personaOverride
    const recorded = this.knownChats.find((c) => c.id === this.chatId)?.personaId
    if (recorded != null && exists(recorded)) return recorded
    return DEFAULT_PERSONA_ID
  }

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
   * Parse a `{"delta": "..."}` SSE payload (text/reasoning events). A
   * malformed payload or a missing/non-string delta yields null — the event
   * is skipped instead of appending "undefined" to the buffer or throwing
   * the whole run into the error path (the `done` reload renders the
   * authoritative stored form anyway).
   */
  private parseDelta(data: string): string | null {
    try {
      const delta = (JSON.parse(data) as { delta?: unknown }).delta
      return typeof delta === 'string' ? delta : null
    } catch {
      return null
    }
  }

  /**
   * Sync the view with whatever the DB actually holds. A failed reload must
   * not mask the run's own error, so keep the current display on failure.
   * Returns whether the reload succeeded.
   */
  private async reloadFromDb(id: string): Promise<boolean> {
    try {
      this.messages = await loadChat(id)
      return true
    } catch {
      // keep the optimistic display; nothing better to show
      return false
    }
  }

  /**
   * Move the live round into the history as an assistant message. No-op when
   * the buffers hold nothing to commit. Callers wipe the streaming buffers
   * afterwards.
   */
  private commitRound() {
    const parts: ChatMessagePart[] = []
    if (this.streamReasoning) parts.push({ type: 'reasoning', content: this.streamReasoning })
    if (this.streamText) parts.push({ type: 'text', text: this.streamText })
    for (const call of this.streamToolCalls) {
      parts.push({ type: 'tool_call', id: '', tool: call.name, args: call.args })
    }
    if (parts.length === 0) return
    this.messages = [...this.messages, { role: 'assistant', parts }]
  }

  /** Record the user's toggle for a collapsible of the round with the given
   * signature (see [partOverridesBySignature]). */
  setPartOverride(signature: string, partKey: string, open: boolean) {
    this.partOverridesBySignature = {
      ...this.partOverridesBySignature,
      [signature]: { ...this.partOverridesBySignature[signature], [partKey]: open },
    }
  }

  /** Drop every user open-state toggle (chat switches): the map is keyed by
   * round signature, not chat id, so a different chat's toggles must not
   * leak in. */
  private clearPartOverrides() {
    this.partOverridesBySignature = {}
  }

  private async loadMessages(id: string) {
    this.streamError = null
    this.chatLoading = true
    try {
      const messages = await loadChat(id)
      // discard the response if the open chat changed mid-flight (a close or
      // switch in the meantime must not render this chat's content under
      // another id — or under none at all), or a run started before the
      // history arrived (the optimistic user message and the run's own
      // reloads own the display then — clobbering it would drop the message)
      if (this.chatId === id && !this.streaming) this.messages = messages
    } catch (e) {
      if (this.chatId === id && !this.streaming) toastStore.pushError(e)
    } finally {
      if (this.chatId === id) this.chatLoading = false
    }
  }

  /**
   * Open a chat (called by the App route effect when the URL changes).
   * Re-picking the open chat is a no-op; on a switch the previous chat's
   * messages are cleared so they never render under the new id while the
   * load is in flight.
   */
  pickChat(id: string) {
    if (id === this.chatId) return
    this.chatId = id
    // the persona override is per-chat: the next chat starts from its record
    this.personaOverride = null
    this.messages = []
    this.clearPartOverrides()
    void this.loadMessages(id)
  }

  /** Close the open chat (the '#/chat' home route): nothing selected. */
  closeChat() {
    this.chatId = ''
    this.personaOverride = null
    this.messages = []
    this.streamError = null
    this.clearPartOverrides()
    // a history load in flight for the closed chat must not leave the flag
    // stuck (its finally skips the clear once the chat id no longer matches)
    this.chatLoading = false
  }

  async createNewChat(): Promise<void> {
    if (this.creatingChat) return
    this.creatingChat = true
    try {
      const id = await newChat()
      this.chatId = id
      this.personaOverride = null
      // prepend to match the server's newest-first order
      this.knownChats = [
        { id, title: DEFAULT_CHAT_TITLE, personaId: DEFAULT_PERSONA_ID },
        ...this.knownChats,
      ]
      this.messages = []
      this.clearPartOverrides()
      // the new chat has no failed run of its own: the previous chat's
      // run-error banner must not carry over (every other switch path
      // clears it — loadMessages / closeChat / forkChat)
      this.streamError = null
      // the new chat is empty by construction: no history fetch starts (the
      // loading placeholder must not stay up for it), and an in-flight load
      // for the previously open chat skips its finally-clear once the id no
      // longer matches
      this.chatLoading = false
      navigate(chatPath(id))
    } catch (e) {
      toastStore.pushError(e)
    } finally {
      this.creatingChat = false
    }
  }

  async renameChat(id: string, title: string): Promise<boolean> {
    if (this.deletingIds.has(id)) return false
    try {
      await renameChat(id, title)
      this.knownChats = this.knownChats.map((c) => (c.id === id ? { ...c, title } : c))
      return true
    } catch (e) {
      toastStore.pushError(e)
      return false
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
      toastStore.pushError(e)
    }
  }

  /**
   * Delete a chat: the backend extracts memories from its history before
   * removing the row, which can take minutes. While the request is in flight
   * the chat is read-only ([deletingIds]) — a second delete call is a no-op.
   * On failure (e.g. the extraction failed and the row is kept) the lock is
   * released and the error surfaces as a toast, so the user can retry.
   */
  async deleteChat(id: string): Promise<void> {
    if (this.deletingIds.has(id)) return
    this.deletingIds.add(id)
    try {
      await deleteChat(id)
      this.knownChats = this.knownChats.filter((c) => c.id !== id)
      this.deletedChatIds.add(id)
      delete this.drafts[id]
      if (this.chatId === id) {
        this.closeChat()
        // leave the deleted chat's route, but don't yank the user back to
        // the chat view when they deleted it from another view; replace the
        // history entry so the deleted chat doesn't survive as a back target
        // (from another view its stale entry stays in history, but the App
        // route effect redirects a later back/forward landing on it to home)
        if (router.current.name === 'chat') replaceRoute(chatHomePath())
      }
    } catch (e) {
      toastStore.pushError(e)
    } finally {
      this.deletingIds.delete(id)
    }
  }

  /**
   * Drop the message at `index` (a user message) and everything after it,
   * WITHOUT memory extraction (a typo'd turn must not leak into memories).
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
      toastStore.pushError(e)
    }
  }

  /**
   * Fork: copy the history up to and including the message at `index` (an
   * assistant message that ended naturally) into a new chat and switch to it.
   * The new chat starts as "New chat" with an empty ELTM state (its first
   * run flags `eltm-updated`), so the original chat stays untouched. The
   * switch happens only while still on the source chat: a chat switch during
   * the request must not hijack the view (the fork chat is still added to
   * the list either way).
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
        this.personaOverride = null
        this.messages = this.messages.slice(0, index + 1)
        this.streamError = null
        // the fork is a fresh chat: no user open-state toggles carry over,
        // and no history fetch starts (the slice IS the history), so the
        // loading placeholder must not stay up for it
        this.clearPartOverrides()
        this.chatLoading = false
        navigate(chatPath(chat.id))
      }
    } catch (e) {
      toastStore.pushError(e)
    } finally {
      this.forkingIds.delete(id)
    }
  }

  async send(
    text: string,
    images: { dataUrl: string }[],
    model: string,
  ): Promise<boolean> {
    const id = this.chatId.trim()
    if (!id) {
      toastStore.push('Select a chat first')
      return false
    }
    // a stale override (its persona was deleted here or in another tab) is a
    // picker artifact: drop it so the effective id resolves from the chat's
    // record, and so a future persona can never silently re-activate it
    if (
      this.personaOverride != null &&
      personaStore.personas.length > 0 &&
      !personaStore.personas.some((p) => p.id === this.personaOverride)
    ) {
      this.personaOverride = null
    }
    const personaId = this.currentPersonaId
    if (this.deletingIds.has(id)) {
      toastStore.push('This chat is being deleted')
      return false
    }
    this.streamError = null
    this.retrying = false
    this.streamReasoning = ''
    this.streamText = ''
    this.streamToolCalls = []
    this.streamReasoningOpen = true
    this.streamToolCallsOpen = []
    this.streaming = true
    // the run owns the display from here on (the optimistic message appended
    // below): an in-flight history load for this chat must not overwrite it,
    // and its loading placeholder must not stay up for a chat with a run
    this.chatLoading = false
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
      for await (const ev of streamChat(id, { text, images, model, personaId })) {
        switch (ev.event) {
          case 'reasoning': {
            this.retrying = false
            const delta = this.parseDelta(ev.data)
            if (delta != null) this.streamReasoning += delta
            break
          }
          case 'text': {
            this.retrying = false
            const delta = this.parseDelta(ev.data)
            if (delta != null) this.streamText += delta
            break
          }
          case 'tool_call':
            this.retrying = false
            this.streamToolCalls = [...this.streamToolCalls, JSON.parse(ev.data)]
            // a new live tool block starts open
            this.streamToolCallsOpen = [...this.streamToolCallsOpen, true]
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
                // commit the round's assistant message, then attach the
                // result
                this.commitRound()
                this.messages = [...this.messages, { role: 'tool_result', parts: [resultPart] }]
                this.streamReasoning = ''
                this.streamText = ''
                this.streamToolCalls = []
                this.streamReasoningOpen = true
                this.streamToolCallsOpen = []
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
            // the retried round's blocks start open again
            this.streamReasoningOpen = true
            this.streamToolCallsOpen = []
            break
          case 'done': {
            sawDone = true
            this.retrying = false
            // hide the live block for the resync: the run is over, and the
            // empty buffers would otherwise render a "Processing…" shimmer
            // while the DB reload + chat-list refresh are in flight
            this.runEnding = true
            if (!(await this.reloadFromDb(id))) {
              // the reload failed: commit the final round into the display
              // (the buffers hold the only copy of the answer) so the view
              // still shows the full run; the next resync or run replaces it
              this.commitRound()
            }
            // The run's streaming buffers are stale in every path (the
            // display is the stored history now). Wipe them so a future
            // render path can never resurrect the last run's partials.
            this.streamReasoning = ''
            this.streamText = ''
            this.streamToolCalls = []
            this.streamReasoningOpen = true
            this.streamToolCallsOpen = []
            // The successful store also stamped the chat's persona record,
            // so refresh the chat list too — the picker would otherwise show
            // the pre-run record until the next 30s/focus resync.
            await this.resyncChats()
            break
          }
          case 'error':
            failed = true
            this.retrying = false
            this.runEnding = true
            // the failed round's partials must not flash under the error
            // banner while the reload below is in flight
            this.streamReasoning = ''
            this.streamText = ''
            this.streamToolCalls = []
            {
              // the server sends {"message": ...} — anything else (a
              // malformed payload, a missing message field) falls back to
              // the raw data instead of throwing a SyntaxError up the stack
              let message: string
              try {
                const parsed = JSON.parse(ev.data) as { message?: unknown }
                message =
                  typeof parsed.message === 'string'
                    ? parsed.message
                    : ev.data
              } catch {
                message = ev.data
              }
              this.streamError = message
            }
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
        this.runEnding = true
        this.streamReasoning = ''
        this.streamText = ''
        this.streamToolCalls = []
        await this.reloadFromDb(id)
        await this.resyncChats()
        this.streamError = 'connection closed before the run completed'
      }
    } catch (e) {
      failed = true
      // a fetch/parse failure may still have stored the run server-side (or
      // not): sync with the DB so a phantom optimistic message never sticks.
      // If the stream already surfaced a run error (streamError), that is
      // the more specific message — the banner or the finally fallback toast
      // carries it, so don't stack a second toast on top
      if (!this.streamError) toastStore.pushError(e)
      this.runEnding = true
      await this.reloadFromDb(id)
      await this.resyncChats()
    } finally {
      this.streaming = false
      this.runEnding = false
      // the pending route may close or switch the chat the moment the stream
      // ends (back/forward or URL edit mid-run): the error banner would be
      // wiped with the view (closeChat / loadMessages clear streamError), so
      // surface the failure as a toast instead of losing it. The eltm/personas
      // routes keep the chat view mounted but CSS-hidden — the banner survives
      // there but the user isn't looking at it, so toast too. Same-route runs
      // keep the banner (the chat stays open). Chat switches are locked
      // while streaming, so `this.chatId` is always `id` here — only the
      // route check below is meaningful.
      if (failed && this.streamError) {
        const route = router.current
        if (route.name !== 'chat' || route.chatId !== id) {
          toastStore.push('run failed: ' + this.streamError, 'error')
        }
      }
    }
    return !failed
  }
}

export const chatStore = new ChatStore()
