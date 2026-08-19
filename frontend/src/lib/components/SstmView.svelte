<script lang="ts">
  import { onMount } from 'svelte'
  import { Pencil, Plus, Trash2 } from '@lucide/svelte'
  import { createMemory, deleteMemory, listMemories, updateMemory } from '../api'
  import type { MemoryDto } from '../types'
  import Button from './ui/button.svelte'
  import { Dialog } from './ui/dialog.svelte'
  import DialogContent from './ui/dialog-content.svelte'
  import DialogDescription from './ui/dialog-description.svelte'
  import DialogFooter from './ui/dialog-footer.svelte'
  import DialogHeader from './ui/dialog-header.svelte'
  import DialogTitle from './ui/dialog-title.svelte'

  let memories = $state<MemoryDto[]>([])
  let error = $state<string | null>(null)
  let newContent = $state('')
  let editingId = $state<number | null>(null)
  let editingContent = $state('')
  // confirm dialog state: either an explicit delete, or a blank edit-save that
  // the backend would turn into a delete
  let deleteCandidate = $state<MemoryDto | null>(null)
  let deleteFromBlankEdit = $state(false)

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

  /**
   * Background resync (30s + window focus, same cadence as the chat list):
   * the run loop's SSTM merges mutate memories server-side, so the view must
   * refresh on its own. Silent on failure (the next tick retries) and
   * replaces the list only when it actually changed, so an in-progress edit
   * keeps its target. Unlike refresh(), it never touches `error`.
   */
  async function resyncMemories() {
    try {
      const fresh = await listMemories()
      if (JSON.stringify(fresh) !== JSON.stringify(memories)) {
        memories = fresh
      }
    } catch {
      // transient backend hiccup: keep the current list
    }
  }

  onMount(() => {
    void refresh()
    const interval = setInterval(() => void resyncMemories(), 30_000)
    window.addEventListener('focus', resyncMemories)
    return () => {
      clearInterval(interval)
      window.removeEventListener('focus', resyncMemories)
    }
  })

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
        editingId = null
        await refresh()
      } else {
        // the backend rejects empty updates, so a blank save would delete the
        // memory — ask first so it's not accidental
        const memory = memories.find((m) => m.id === editingId)
        if (memory) {
          deleteCandidate = memory
          deleteFromBlankEdit = true
        }
      }
    } catch (e) {
      error = String(e)
    }
  }

  function askDelete(memory: MemoryDto) {
    deleteCandidate = memory
    deleteFromBlankEdit = false
  }

  async function doDelete() {
    const candidate = deleteCandidate
    if (!candidate) return
    try {
      await deleteMemory(candidate.id)
      if (editingId === candidate.id) editingId = null
      deleteCandidate = null
      deleteFromBlankEdit = false
      await refresh()
    } catch (e) {
      error = String(e)
    }
  }

  function formatDate(iso: string): string {
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return iso
    return d.toLocaleString()
  }
</script>

<div class="h-full overflow-y-auto">
  <div class="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-8">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">SSTM</h1>
      <p class="text-sm text-muted-foreground">Shared short-term memories injected into every chat run</p>
    </div>

    {#if error}
      <div class="break-words rounded-lg border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
        {error}
      </div>
    {/if}

    <form
      class="rounded-3xl border border-border/30 bg-muted/60 p-3 shadow-sm backdrop-blur-md transition-all focus-within:border-border focus-within:shadow-md"
      onsubmit={(e) => {
        e.preventDefault()
        void add()
      }}
    >
      <textarea
        bind:value={newContent}
        rows="3"
        placeholder="Add a memory…"
        class="w-full resize-none border-0 bg-transparent px-2 py-1 text-sm leading-6 outline-none placeholder:text-muted-foreground"
      ></textarea>
      <div class="flex justify-end pt-1">
        <button
          type="submit"
          disabled={!newContent.trim()}
          title="add memory"
          class="flex size-9 items-center justify-center rounded-full bg-primary text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-40"
        >
          <Plus class="size-4" />
        </button>
      </div>
    </form>

    {#if memories.length === 0}
      <div class="py-10 text-center text-sm text-muted-foreground">no memories yet</div>
    {/if}

    {#each memories as memory}
      <div class="rounded-2xl border border-border/30 bg-muted/60 p-4 shadow-sm backdrop-blur-md">
        <div class="mb-2 flex items-center justify-between gap-2">
          <span class="text-xs text-muted-foreground tabular-nums">#{memory.id} · {formatDate(memory.lastUpdate)}</span>
          <div class="flex shrink-0 gap-1">
            {#if editingId === memory.id}
              <Button size="sm" variant="ghost" onclick={() => (editingId = null)}>Cancel</Button>
              <Button size="sm" onclick={() => void saveEdit()}>Save</Button>
            {:else}
              <Button size="sm" variant="ghost" onclick={() => startEdit(memory)}>
                <Pencil class="size-3" />
                Edit
              </Button>
              <Button
                size="sm"
                variant="ghost"
                class="text-destructive hover:bg-destructive/10 hover:text-destructive"
                onclick={() => askDelete(memory)}
              >
                <Trash2 class="size-3" />
                Delete
              </Button>
            {/if}
          </div>
        </div>
        {#if editingId === memory.id}
          <textarea
            bind:value={editingContent}
            rows="4"
            class="w-full resize-y rounded-lg border border-border/50 bg-transparent p-2 text-sm leading-6 outline-none transition focus:border-ring"
          ></textarea>
        {:else}
          <div class="whitespace-pre-wrap break-words text-sm leading-6">{memory.content}</div>
        {/if}
      </div>
    {/each}
  </div>
</div>

<Dialog
  open={deleteCandidate !== null}
  onOpenChange={(open: boolean) => {
    if (!open) {
      deleteCandidate = null
      deleteFromBlankEdit = false
    }
  }}
>
  <DialogContent>
    <DialogHeader>
      <div class="flex items-center gap-3">
        <div class="flex size-9 shrink-0 items-center justify-center rounded-full bg-destructive/15 text-destructive">
          <Trash2 class="size-4" />
        </div>
        <div class="min-w-0">
          <DialogTitle>Delete memory?</DialogTitle>
          <DialogDescription>
            {deleteFromBlankEdit
              ? 'Empty content — the backend rejects empty updates, so saving a blank edit deletes the memory.'
              : `Memory #${deleteCandidate?.id ?? ''} will be permanently deleted.`}
          </DialogDescription>
        </div>
      </div>
    </DialogHeader>
    <DialogFooter>
      <Button
        variant="ghost"
        onclick={() => {
          deleteCandidate = null
          deleteFromBlankEdit = false
        }}
      >
        Cancel
      </Button>
      <Button variant="destructive" onclick={() => void doDelete()}>Delete</Button>
    </DialogFooter>
  </DialogContent>
</Dialog>
