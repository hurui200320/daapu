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
import type { ChatInfo, ChatMessage, ChatMessagePart, ModelInfo } from './types'
import { DEFAULT_PERSONA_ID } from './types'
import { chatHomePath, chatPath, navigate, replaceRoute, router } from './router.svelte'
import { personaStore } from './persona-store.svelte'
import { onIntervalAndFocus } from './resync'
import { toastStore } from './toast-store.svelte'
import { dataUrlToImagePart } from './display'
import { StreamSession } from './stream-session'

// mirrors the backend's DEFAULT_CHAT_TITLE (db/Tables.kt)
const DEFAULT_CHAT_TITLE = 'New chat'

// The model picker's persistence key. The restore/write-back lives in
// App.svelte via $effect: the store is a module-scope singleton, and `$effect`
// runes are only valid inside a component.
export const MODEL_STORAGE_KEY = 'daapu.model'

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

  // truncations in flight per chat id: indices computed on the display list
  // go stale once a truncate lands, so while one is pending every OTHER
  // history edit (truncate/fork) on this chat stays disabled — mirroring how
  // fork/truncate disable during a full-chat delete's memory extraction
  truncatingIds = new SvelteSet<string>()

  /**
   * True while ANY history mutation (full-chat delete / fork / truncate) is
   * in flight on this chat: the other edits' display indices go stale until
   * it settles, so every edit entry point must gate on this ONE predicate —
   * adding a future mutation kind means adding it here, not finding the
   * inline triples.
   */
  isMutatingHistory(id: string): boolean {
    return this.deletingIds.has(id) || this.forkingIds.has(id) || this.truncatingIds.has(id)
  }

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
    const exists = (id: number) => personaStore.personas.length === 0 || personaStore.personas.some((p) => p.id === id)
    if (this.personaOverride != null && exists(this.personaOverride)) return this.personaOverride
    const recorded = this.knownChats.find((c) => c.id === this.chatId)?.personaId
    if (recorded != null && exists(recorded)) return recorded
    return DEFAULT_PERSONA_ID
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

  /**
   * Reset the in-flight round's ENTIRE display state to its fresh-send shape
   * (partials empty, reasoning reopened, tool-call blocks closed): the retry
   * wipe, every terminal path and `send()` itself share it.
   */
  private resetLiveRound() {
    this.streamReasoning = ''
    this.streamText = ''
    this.streamToolCalls = []
    this.streamReasoningOpen = true
    this.streamToolCallsOpen = []
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
      this.knownChats = [{ id, title: DEFAULT_CHAT_TITLE, personaId: DEFAULT_PERSONA_ID }, ...this.knownChats]
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
   * streaming anyway, so the local slice mirrors the DB exactly. Like any
   * history edit, it is skipped while a full-chat delete, a fork or another
   * truncation on this chat is still in flight.
   *
   * Returns whether the truncation was performed: false either on an API
   * error (toasted here) or on a guarded no-op — another history edit in
   * flight on this chat, toasted like `send`. The caller must NOT treat it
   * as success.
   */
  async truncateMessages(chatId: string, index: number): Promise<boolean> {
    if (!chatId || this.isMutatingHistory(chatId)) {
      toastStore.push('A history edit is in progress')
      return false
    }
    this.truncatingIds.add(chatId)
    try {
      await apiTruncateMessages(chatId, index)
      if (this.chatId === chatId) {
        this.messages = this.messages.slice(0, index)
        // the failed turn is gone, so the run-failure banner goes with it
        this.streamError = null
      }
      return true
    } catch (e) {
      toastStore.pushError(e)
      return false
    } finally {
      this.truncatingIds.delete(chatId)
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
    if (!id || this.isMutatingHistory(id)) return
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

  async send(text: string, images: { dataUrl: string }[], model: string): Promise<boolean> {
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
    // serialize against history edits like the message-item buttons do: a
    // pending truncate/fork shifts indices (then the stored history) under
    // an optimistic send
    if (this.isMutatingHistory(id)) {
      toastStore.push('A history edit is in progress')
      return false
    }
    this.streamError = null
    this.retrying = false
    this.resetLiveRound()
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
      const part = dataUrlToImagePart(image.dataUrl)
      if (part) userParts.push(part)
    }
    this.messages = [...this.messages, { role: 'user', parts: userParts }]
    // The event semantics live in StreamSession (unit-tested against scripted
    // SSE scripts); this class only supplies the reactive-state verbs.
    let failed = false
    try {
      const outcome = await new StreamSession(
        {
          events: streamChat(id, { text, images, model, personaId }),
          reloadFromDb: () => this.reloadFromDb(id),
          resyncChats: () => this.resyncChats(),
        },
        {
          onTextDelta: (delta) => {
            this.retrying = false
            this.streamText += delta
          },
          onReasoningDelta: (delta) => {
            this.retrying = false
            this.streamReasoning += delta
          },
          onToolCall: (call) => {
            this.retrying = false
            this.streamToolCalls = [...this.streamToolCalls, call]
            // a new live tool block starts open
            this.streamToolCallsOpen = [...this.streamToolCallsOpen, true]
          },
          onToolResult: (resultPart) => {
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
            const last = this.messages[this.messages.length - 1]
            if (this.streamToolCalls.length === 0 && last?.role === 'tool_result') {
              // 2nd..Nth result of the same batch: extend the committed
              // tool_result message instead of creating an empty pair
              this.messages = [...this.messages.slice(0, -1), { ...last, parts: [...last.parts, resultPart] }]
            } else {
              // commit the round's assistant message, then attach the result
              this.commitRound()
              this.messages = [...this.messages, { role: 'tool_result', parts: [resultPart] }]
              // the next round streams as its own message
              this.resetLiveRound()
            }
          },
          onRetryBegin: () => {
            this.retrying = true
            // the retried round's blocks start open again (resetLiveRound)
            this.resetLiveRound()
          },
          beginRunEnding: () => {
            this.retrying = false
            this.runEnding = true
          },
          resetLiveRound: () => this.resetLiveRound(),
          commitFinalRound: () => this.commitRound(),
          setRunError: (message) => (this.streamError = message),
          // falsy check (NOT just != null) so '' suppresses the fallback
          // toast too, like the legacy `if (!this.streamError)` did
          hasRunError: () => !!this.streamError,
          toastTransportFailure: (e) => toastStore.pushError(e),
        },
      ).run()
      failed = outcome.failed
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
