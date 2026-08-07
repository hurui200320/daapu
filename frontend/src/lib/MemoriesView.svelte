<script lang="ts">
  import { onMount } from 'svelte'
  import { createMemory, deleteMemory, listMemories, updateMemory } from './api'
  import type { MemoryDto } from './types'

  let memories = $state<MemoryDto[]>([])
  let error = $state<string | null>(null)
  let newContent = $state('')
  let editingId = $state<number | null>(null)
  let editingContent = $state('')

  async function refresh() {
    // every mutation ends in refresh(): clear stale errors so a failed op
    // doesn't leave a permanent banner after later successes
    error = null
    try {
      memories = await listMemories()
    } catch (e) {
      error = String(e)
    }
  }

  onMount(refresh)

  async function add() {
    const content = newContent.trim()
    if (!content) return
    try {
      await createMemory(content)
      newContent = ''
      await refresh()
    } catch (e) {
      error = String(e)
    }
  }

  function startEdit(memory: MemoryDto) {
    editingId = memory.id
    editingContent = memory.content
  }

  async function saveEdit() {
    if (editingId === null) return
    const content = editingContent.trim()
    try {
      if (content) {
        await updateMemory(editingId, content)
      } else {
        // the backend rejects empty updates, so a blank save would delete the
        // memory — ask first so it's not accidental
        if (!confirm('Empty content — delete this memory?')) return
        await deleteMemory(editingId)
      }
      editingId = null
      await refresh()
    } catch (e) {
      error = String(e)
    }
  }

  async function remove(id: number) {
    try {
      await deleteMemory(id)
      await refresh()
    } catch (e) {
      error = String(e)
    }
  }
</script>

<div class="memories">
  <div class="add-row">
    <textarea bind:value={newContent} rows="2" placeholder="new shared short term memory"></textarea>
    <button onclick={() => void add()} disabled={!newContent.trim()}>add</button>
  </div>
  {#if error}<div class="error">{error}</div>{/if}
  {#if memories.length === 0}
    <div class="empty">no memories yet</div>
  {/if}
  {#each memories as memory}
    <div class="memory">
      <div class="meta">
        <span>#{memory.id}</span>
        <span>{memory.lastUpdate}</span>
      </div>
      {#if editingId === memory.id}
        <textarea bind:value={editingContent} rows="3"></textarea>
        <div class="actions">
          <button onclick={() => void saveEdit()}>save</button>
          <button onclick={() => (editingId = null)}>cancel</button>
        </div>
      {:else}
        <div class="content">{memory.content}</div>
        <div class="actions">
          <button onclick={() => startEdit(memory)}>edit</button>
          <button class="danger" onclick={() => void remove(memory.id)}>delete</button>
        </div>
      {/if}
    </div>
  {/each}
</div>

<style>
  .memories {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
    padding: 1rem;
    overflow-y: auto;
  }

  .add-row {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  textarea {
    padding: 0.5rem 0.7rem;
    border: 1px solid var(--border);
    border-radius: 0.6rem;
    background: var(--input-bg);
    color: var(--text);
    font: inherit;
    resize: vertical;
  }

  .memory {
    border: 1px solid var(--border);
    border-radius: 0.6rem;
    padding: 0.6rem 0.8rem;
    display: flex;
    flex-direction: column;
    gap: 0.4rem;
  }

  .meta {
    display: flex;
    gap: 0.6rem;
    color: var(--text-muted);
    font-size: 0.8rem;
  }

  .content {
    white-space: pre-wrap;
    word-break: break-word;
    line-height: 1.5;
  }

  .actions {
    display: flex;
    gap: 0.4rem;
  }

  button {
    font: inherit;
    border: 1px solid var(--border);
    border-radius: 0.5rem;
    padding: 0.35rem 0.7rem;
    background: var(--input-bg);
    color: var(--text);
    cursor: pointer;
  }

  button:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .danger {
    color: var(--danger-fg);
    border-color: var(--danger-border);
  }

  .error {
    color: var(--danger-fg);
  }

  .empty {
    color: var(--text-muted);
    text-align: center;
    padding: 2rem;
  }
</style>
