<script lang="ts">
  import { onMount } from 'svelte'
  import { deleteChat, listChats, listModels, loadChat, newChat, renameChat, streamChat } from './api'
  import type { ChatInfo, ChatMessage, ChatMessagePart, ChatToolResultPart, ModelInfo } from './types'
  import Composer from './Composer.svelte'
  import MessageList from './MessageList.svelte'
  import UsageBar from './UsageBar.svelte'

  let chatId = $state('')
  let knownChats = $state<ChatInfo[]>([])
  let models = $state<ModelInfo[]>([])
  let messages = $state<ChatMessage[]>([])
  let error = $state<string | null>(null)

  // the per-message model picker lives in the Composer; the state is lifted
  // here so the usage bar can show the context of the model that will process
  // the next message
  let selectedModel = $state('')

  let streaming = $state(false)
  let streamReasoning = $state('')
  let streamText = $state('')
  // tool calls of the round currently being streamed (uncommitted: wiped on retry)
  let streamToolCalls = $state<{ name: string; args: string }[]>([])
  let retrying = $state(false)
  let streamError = $state<string | null>(null)

  // mirrors the backend's data-URL handling (ChatRunService.parseImagePart:
  // trims the URL and strips whitespace from the base64 payload)
  const DATA_URL_RE = /^data:(image\/[a-zA-Z0-9.+-]+);base64,([\s\S]+)$/

  // mirrors the backend's DEFAULT_CHAT_TITLE (db/Tables.kt)
  const DEFAULT_CHAT_TITLE = 'New chat'

  const STORAGE_KEY = 'daapu.model'

  /** The currently selected chat's entry (absent for a stale/missing id). */
  const currentChat = $derived(knownChats.find((c) => c.id === chatId))

  $effect(() => {
    if (models.length > 0 && selectedModel === '') {
      const stored = localStorage.getItem(STORAGE_KEY)
      // a stale stored id (catalog changed, different backend) would render a
      // blank picker and a confusing 400 on send: fall back to the first model
      selectedModel = models.some((m) => m.id === stored) ? stored! : models[0].id
    }
  })

  $effect(() => {
    if (selectedModel) localStorage.setItem(STORAGE_KEY, selectedModel)
  })

  /**
   * Latest-round usage: scan backwards for the last assistant message that
   * carries token usage (each round's total = full prompt + output of that
   * round, the best proxy for current context occupancy). The context window
   * is always the currently selected model's, since the next message will be
   * processed by it. Missing either side (no usage data, unknown/null
   * contextLength) hides the indicator.
   */
  function computeUsage(): { used: number | null; context: number | null } {
    for (let i = messages.length - 1; i >= 0; i--) {
      const msg = messages[i]
      const used = msg.role === 'assistant' ? msg.meta?.totalTokens : null
      if (used == null) continue
      const model = models.find((m) => m.id === selectedModel)
      return { used, context: model?.contextLength ?? null }
    }
    return { used: null, context: null }
  }

  const usage = $derived(computeUsage())

  /** Mirror of the backend's ChatMessagePart.Attachment, for display only. */
  function dataUrlToPart(dataUrl: string): ChatMessagePart | null {
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
  async function reloadFromDb(id: string) {
    try {
      messages = await loadChat(id)
    } catch {
      // keep the optimistic display; nothing better to show
    }
  }

  onMount(async () => {
    try {
      models = await listModels()
      knownChats = await listChats()
    } catch (e) {
      error = String(e)
    }
  })

  async function loadMessages(id: string) {
    error = null
    streamError = null
    try {
      messages = await loadChat(id)
    } catch (e) {
      error = String(e)
    }
  }

  function pickChat(id: string) {
    chatId = id
    void loadMessages(id)
  }

  async function createNewChat() {
    error = null
    try {
      chatId = await newChat()
      // prepend to match the server's newest-first order
      knownChats = [{ id: chatId, title: DEFAULT_CHAT_TITLE }, ...knownChats]
      messages = []
    } catch (e) {
      error = String(e)
    }
  }

  async function renameCurrentChat() {
    if (!chatId) return
    const current = currentChat
    const input = prompt(
      `Rename chat${current ? ` "${current.title}"` : ''}:`,
      current?.title ?? ''
    )
    if (input == null) return
    const title = input.trim()
    if (!title) {
      error = 'Chat title must not be empty'
      return
    }
    error = null
    try {
      await renameChat(chatId, title)
      knownChats = knownChats.map((c) => (c.id === chatId ? { ...c, title } : c))
    } catch (e) {
      error = String(e)
    }
  }

  async function deleteCurrentChat() {
    if (!chatId) return
    const label = currentChat ? `"${currentChat.title}"` : chatId
    if (!confirm(`Delete chat ${label}?`)) return
    error = null
    try {
      await deleteChat(chatId)
      knownChats = knownChats.filter((c) => c.id !== chatId)
      chatId = ''
      messages = []
    } catch (e) {
      error = String(e)
    }
  }

  async function send(text: string, images: { dataUrl: string }[], model: string): Promise<boolean> {
    const id = chatId.trim()
    if (!id) {
      error = 'Select a chat first'
      return false
    }
    error = null
    streamError = null
    retrying = false
    streamReasoning = ''
    streamText = ''
    streamToolCalls = []
    streaming = true
    // optimistic: show the sent message right away; the final reload replaces
    // it with the stored form (and a failed run never reaches the store, so
    // the error/connection-closed reloads below drop it again)
    const userParts: ChatMessagePart[] = []
    if (text) userParts.push({ type: 'text', text })
    for (const image of images) {
      const part = dataUrlToPart(image.dataUrl)
      if (part) userParts.push(part)
    }
    messages = [...messages, { role: 'user', parts: userParts }]
    let failed = false
    let sawDone = false
    try {
      for await (const ev of streamChat(id, { text, images, model })) {
        switch (ev.event) {
          case 'reasoning':
            retrying = false
            streamReasoning += JSON.parse(ev.data).delta
            break
          case 'text':
            retrying = false
            streamText += JSON.parse(ev.data).delta
            break
          case 'tool_call':
            retrying = false
            streamToolCalls = [...streamToolCalls, JSON.parse(ev.data)]
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
              const last = messages[messages.length - 1]
              if (streamToolCalls.length === 0 && last?.role === 'tool_result') {
                // 2nd..Nth result of the same batch: extend the committed
                // tool_result message instead of creating an empty pair
                messages = [
                  ...messages.slice(0, -1),
                  { ...last, parts: [...last.parts, resultPart] },
                ]
              } else {
                const parts: ChatMessagePart[] = []
                if (streamReasoning) parts.push({ type: 'reasoning', content: [streamReasoning] })
                if (streamText) parts.push({ type: 'text', text: streamText })
                for (const call of streamToolCalls) {
                  parts.push({ type: 'tool_call', id: '', tool: call.name, args: call.args })
                }
                messages = [
                  ...messages,
                  { role: 'assistant', parts },
                  { role: 'tool_result', parts: [resultPart] },
                ]
                streamReasoning = ''
                streamText = ''
                streamToolCalls = []
              }
            }
            break
          case 'retry':
            // the server re-streams the whole round from scratch; drop the
            // failed round's partials so the retried output doesn't append to
            // stale text (committed rounds live in `messages` and survive)
            retrying = true
            streamReasoning = ''
            streamText = ''
            streamToolCalls = []
            break
          case 'done':
            sawDone = true
            retrying = false
            // the server stores history only on success; reload it so the UI
            // always matches the DB (covers tool-call rounds too). Uses the
            // captured id, not the mutable input state.
            await reloadFromDb(id)
            break
          case 'error':
            failed = true
            retrying = false
            streamError = JSON.parse(ev.data).message ?? ev.data
            // the run failed before storing, so the optimistic user message
            // and any committed tool rounds are not in the DB: reload to match
            await reloadFromDb(id)
            break
        }
      }
      if (!sawDone && !failed) {
        // the connection closed cleanly without a terminal event (e.g. the
        // server restarted mid-run): the run's outcome is unknown, so sync
        // with whatever the DB actually has and restore the draft
        failed = true
        await reloadFromDb(id)
        streamError = 'connection closed before the run completed'
      }
    } catch (e) {
      failed = true
      error = String(e)
      // a fetch/parse failure may still have stored the run server-side (or
      // not): sync with the DB so a phantom optimistic message never sticks
      await reloadFromDb(id)
    } finally {
      streaming = false
    }
    return !failed
  }
</script>

<div class="chat">
  <div class="chat-bar">
    <code class="current-chat" title={chatId ? `chat id: ${chatId}` : undefined}>
      {currentChat?.title ?? (chatId || 'no chat selected')}
    </code>
    <select
      value=""
      disabled={streaming}
      onchange={(e) => {
        const select = e.currentTarget as HTMLSelectElement
        const id = select.value
        // reset the picker after each pick so re-selecting the same chat
        // fires again (acts as a refresh)
        select.value = ''
        if (id) pickChat(id)
      }}
      title="load chat (re-select the same chat to refresh)"
    >
      <option value="" disabled>load chat…</option>
      {#each knownChats as info}
        <option value={info.id} title={info.id}>{info.title}</option>
      {/each}
    </select>
    <button onclick={() => void createNewChat()} disabled={streaming}>new chat</button>
    <button onclick={() => void renameCurrentChat()} disabled={streaming || !chatId}>rename</button>
    <button onclick={() => void deleteCurrentChat()} disabled={streaming || !chatId}>delete</button>
    <UsageBar used={usage.used} context={usage.context} />
    {#if error}<span class="error">{error}</span>{/if}
  </div>
  <MessageList
    {messages}
    {streaming}
    streamReasoning={streamReasoning}
    streamText={streamText}
    streamToolCalls={streamToolCalls}
    {retrying}
    streamError={streamError}
  />
  <Composer {models} disabled={streaming} onSend={send} bind:selectedModel />
</div>

<style>
  .chat {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-height: 0;
  }

  .chat-bar {
    display: flex;
    gap: 0.5rem;
    align-items: center;
    padding: 0.6rem;
    border-bottom: 1px solid var(--border);
  }

  .chat-bar select {
    flex: 1;
    padding: 0.45rem 0.7rem;
    border: 1px solid var(--border);
    border-radius: 0.6rem;
    background: var(--input-bg);
    color: var(--text);
    font: inherit;
    max-width: 24rem;
  }

  .chat-bar button {
    font: inherit;
    border: 1px solid var(--border);
    border-radius: 0.6rem;
    padding: 0.45rem 0.7rem;
    background: var(--input-bg);
    color: var(--text);
    cursor: pointer;
  }

  .chat-bar select:disabled,
  .chat-bar button:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .current-chat {
    max-width: 16rem;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-muted);
    font-size: 0.85rem;
  }

  .error {
    color: var(--danger-fg);
    font-size: 0.9rem;
    max-width: 20rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
</style>
