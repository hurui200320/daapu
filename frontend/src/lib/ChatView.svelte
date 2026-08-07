<script lang="ts">
  import { onMount } from 'svelte'
  import { deleteChat, listChats, listModels, loadHistory, newChat, streamChat } from './api'
  import type { KoogMessage, ModelInfo } from './types'
  import Composer from './Composer.svelte'
  import MessageList from './MessageList.svelte'

  let chatId = $state('')
  let knownChats = $state<string[]>([])
  let models = $state<ModelInfo[]>([])
  let messages = $state<KoogMessage[]>([])
  let error = $state<string | null>(null)

  let streaming = $state(false)
  let streamReasoning = $state('')
  let streamText = $state('')
  // tool calls of the round currently being streamed (uncommitted: wiped on retry)
  let streamToolCalls = $state<{ name: string; args: string }[]>([])
  // tool calls of finished rounds, whose results were appended to the prompt
  // (committed: a later retry must not wipe them — the results stay in history)
  let committedToolCalls = $state<{ name: string; args: string }[]>([])
  let streamToolResults = $state<{ id: string; name: string; content: string; isError: boolean }[]>([])
  let retrying = $state(false)
  let streamError = $state<string | null>(null)

  let displayedToolCalls = $derived([...committedToolCalls, ...streamToolCalls])

  onMount(async () => {
    try {
      models = await listModels()
      knownChats = await listChats()
    } catch (e) {
      error = String(e)
    }
  })

  async function loadChat(id: string) {
    error = null
    streamError = null
    try {
      messages = await loadHistory(id)
    } catch (e) {
      error = String(e)
    }
  }

  function pickChat(id: string) {
    chatId = id
    void loadChat(id)
  }

  async function createNewChat() {
    error = null
    try {
      chatId = await newChat()
      // prepend to match the server's newest-first order
      knownChats = [chatId, ...knownChats]
      // the row only appears after the first successful run; history is empty
      messages = []
    } catch (e) {
      error = String(e)
    }
  }

  async function deleteCurrentChat() {
    if (!chatId) return
    if (!confirm(`Delete chat ${chatId}?`)) return
    error = null
    try {
      await deleteChat(chatId)
      knownChats = knownChats.filter((c) => c !== chatId)
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
    committedToolCalls = []
    streamToolResults = []
    streaming = true
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
            // a tool result implies its round's tool calls were committed to
            // the prompt too: move them out of the current round so a later
            // retry doesn't wipe them
            committedToolCalls = [...committedToolCalls, ...streamToolCalls]
            streamToolCalls = []
            streamToolResults = [...streamToolResults, JSON.parse(ev.data)]
            break
          case 'retry':
            // the server re-streams the whole round from scratch; drop the
            // failed round's partials so the retried output doesn't append to
            // stale text (committed tool calls + results are kept)
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
            await loadChat(id)
            break
          case 'error':
            failed = true
            retrying = false
            streamError = JSON.parse(ev.data).message ?? ev.data
            break
        }
      }
      if (!sawDone && !failed) {
        // the connection closed cleanly without a terminal event (e.g. the
        // server restarted mid-run): the run's outcome is unknown, so sync
        // with whatever the DB actually has and restore the draft
        failed = true
        await loadChat(id)
        streamError = 'connection closed before the run completed'
      }
    } catch (e) {
      failed = true
      error = String(e)
    } finally {
      streaming = false
    }
    return !failed
  }
</script>

<div class="chat">
  <div class="chat-bar">
    <code class="current-chat" title="current chat id">{chatId || 'no chat selected'}</code>
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
      {#each knownChats as id}
        <option value={id}>{id}</option>
      {/each}
    </select>
    <button onclick={() => void createNewChat()} disabled={streaming}>new chat</button>
    <button onclick={() => void deleteCurrentChat()} disabled={streaming || !chatId}>delete</button>
    {#if error}<span class="error">{error}</span>{/if}
  </div>
  <MessageList
    {messages}
    {streaming}
    streamReasoning={streamReasoning}
    streamText={streamText}
    streamToolCalls={displayedToolCalls}
    streamToolResults={streamToolResults}
    {retrying}
    streamError={streamError}
  />
  <Composer {models} disabled={streaming} onSend={send} />
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
