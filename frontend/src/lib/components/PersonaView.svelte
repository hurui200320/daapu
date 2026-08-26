<script lang="ts">
  import { Check, ChevronDown, ChevronRight, Copy, Loader2, Pencil, Plus, Tags, Trash2, UserRound } from '@lucide/svelte'
  import Button from './ui/button.svelte'
  import { Dialog } from './ui/dialog.svelte'
  import DialogContent from './ui/dialog-content.svelte'
  import DialogDescription from './ui/dialog-description.svelte'
  import DialogFooter from './ui/dialog-footer.svelte'
  import DialogHeader from './ui/dialog-header.svelte'
  import DialogTitle from './ui/dialog-title.svelte'
  import MarkdownContent from './MarkdownContent.svelte'
  import { personaStore } from '../persona-store.svelte'
  import type { Persona } from '../types'
  import { DEFAULT_PERSONA_ID } from '../types'

  /**
   * Persona management (`#/personas`): browse-only default persona (it lives
   * in code) plus the editable `personas` rows — id, name, namespace
   * whitelist — each with a markdown preview dropdown, an edit-prompt and an
   * edit-namespaces dialog, and a delete action. Writes are validated by the
   * backend (400 → toast).
   */

  // null = dialog closed; 'new' = create mode; Persona = edit mode
  let promptEditor = $state<Persona | 'new' | null>(null)
  let namespacesTarget = $state<Persona | null>(null)
  let deleteTarget = $state<Persona | null>(null)

  // per-row markdown preview dropdowns, keyed by persona id
  let previewExpanded = $state<Record<number, boolean>>({})

  // the persona id whose raw text was just copied ("Copied!" feedback)
  let copiedId = $state<number | null>(null)

  // form state of the prompt editor (seeded on open via the dialog binding)
  let editorName = $state('')
  let editorPrompt = $state('')
  let editorBusy = $state(false)

  // form state of the namespaces editor: one text input per item. Items carry
  // a stable row id (not the array index), so removing a middle row cannot
  // shift the two-way bindings onto the wrong inputs
  let namespaceItems = $state<{ id: number; value: string }[]>([])
  let namespaceRowId = 0
  let namespacesBusy = $state(false)

  const namespacesText = (p: Persona): string =>
    p.allowedNamespaces.length === 0 ? 'all namespaces' : p.allowedNamespaces.join(', ')

  function openPromptEditor(persona: Persona | 'new') {
    promptEditor = persona
    editorName = persona === 'new' ? '' : persona.name
    editorPrompt = persona === 'new' ? '' : persona.systemPrompt
  }

  async function savePromptEditor() {
    const target = promptEditor
    if (!target || editorBusy) return
    editorBusy = true
    let ok: boolean
    if (target === 'new') {
      ok = await personaStore.create(editorName, editorPrompt, [])
    } else {
      // merge onto the FRESH row: the 30s resync or another tab may have
      // edited the fields this dialog does not touch while it was open —
      // saving the captured copy would silently clobber those edits
      const fresh = personaStore.personas.find((p) => p.id === target.id) ?? target
      ok = await personaStore.update(target.id, editorName, editorPrompt, fresh.allowedNamespaces)
    }
    editorBusy = false
    if (ok) promptEditor = null
  }

  function openNamespacesEditor(persona: Persona) {
    namespacesTarget = persona
    namespaceItems = persona.allowedNamespaces.map((value) => ({ id: namespaceRowId++, value }))
  }

  async function saveNamespacesEditor() {
    const target = namespacesTarget
    if (!target || namespacesBusy) return
    namespacesBusy = true
    // merge onto the FRESH row (same reason as savePromptEditor)
    const fresh = personaStore.personas.find((p) => p.id === target.id) ?? target
    const items = namespaceItems.map((i) => i.value.trim()).filter((i) => i.length > 0)
    const ok = await personaStore.update(target.id, fresh.name, fresh.systemPrompt, items)
    namespacesBusy = false
    if (ok) namespacesTarget = null
  }

  function confirmDelete() {
    const target = deleteTarget
    if (!target) return
    void personaStore.delete(target.id)
    deleteTarget = null
  }

  function togglePreview(id: number) {
    previewExpanded = { ...previewExpanded, [id]: !previewExpanded[id] }
  }

  async function copyRaw(id: number, text: string) {
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      // clipboard permission denied; no "Copied!" feedback
      return
    }
    copiedId = id
    setTimeout(() => {
      if (copiedId === id) copiedId = null
    }, 1500)
  }
</script>

<div class="mx-auto w-full max-w-3xl px-4 pb-4 pt-6">
  <div class="mb-4 flex items-center justify-between gap-2">
    <div>
      <h1 class="text-lg font-semibold tracking-tight">Personas</h1>
      <p class="text-xs text-muted-foreground">
        The persona text is prepended to the GSG harness introduction; the namespace whitelist
        restricts which tools the chat loop serves (empty = all).
      </p>
    </div>
    <Button size="sm" onclick={() => openPromptEditor('new')}>
      <Plus class="size-4" />
      New persona
    </Button>
  </div>

  <div class="space-y-2">
    {#each personaStore.personas as persona (persona.id)}
      <div class="rounded-xl border border-border/60 bg-muted/40 transition-colors hover:bg-muted/70">
        <div class="flex items-center gap-1.5 px-2.5 py-2.5">
          <button
            class="inline-flex size-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
            title={previewExpanded[persona.id] ? 'hide system prompt preview' : 'preview system prompt'}
            onclick={() => togglePreview(persona.id)}
          >
            {#if previewExpanded[persona.id]}
              <ChevronDown class="size-4" />
            {:else}
              <ChevronRight class="size-4" />
            {/if}
          </button>
          <UserRound class="size-4 shrink-0 text-muted-foreground" />
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2">
              <span class="truncate text-sm font-medium">{persona.name}</span>
              {#if persona.id === DEFAULT_PERSONA_ID}
                <span class="shrink-0 rounded bg-muted px-1.5 py-0.5 text-[0.65rem] text-muted-foreground">
                  built-in
                </span>
              {/if}
            </div>
            <div class="truncate text-xs text-muted-foreground">
              <span class="font-mono">{persona.id}</span> · {namespacesText(persona)}
            </div>
          </div>
          {#if persona.id !== DEFAULT_PERSONA_ID}
            <button
              class="inline-flex size-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
              title="edit system prompt"
              onclick={() => openPromptEditor(persona)}
            >
              <Pencil class="size-4" />
            </button>
            <button
              class="inline-flex size-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
              title="edit allowed namespaces"
              onclick={() => openNamespacesEditor(persona)}
            >
              <Tags class="size-4" />
            </button>
            <button
              class="inline-flex size-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-destructive/10 hover:text-destructive focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
              title="delete persona"
              onclick={() => (deleteTarget = persona)}
            >
              <Trash2 class="size-4" />
            </button>
          {/if}
        </div>
        {#if previewExpanded[persona.id]}
          <div class="border-t border-border/60 px-5 py-3">
            <div class="mb-2 flex items-center justify-between gap-2">
              <span class="text-xs font-medium text-muted-foreground">System prompt</span>
              <button
                class="inline-flex h-7 shrink-0 items-center gap-1.5 rounded-md px-2 text-xs text-muted-foreground transition hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
                title="copy the unrendered content"
                onclick={() => copyRaw(persona.id, persona.systemPrompt)}
              >
                {#if copiedId === persona.id}
                  <Check class="size-3.5" />
                  Copied!
                {:else}
                  <Copy class="size-3.5" />
                  Copy
                {/if}
              </button>
            </div>
            <MarkdownContent text={persona.systemPrompt} />
          </div>
        {/if}
      </div>
    {:else}
      <div class="px-2 py-10 text-center text-xs text-muted-foreground">no personas yet</div>
    {/each}
  </div>
</div>

<!-- system prompt editor: create mode (Persona 'new') and edit mode -->
<Dialog open={promptEditor !== null} onOpenChange={(open: boolean) => !open && (promptEditor = null)}>
  <DialogContent>
    <DialogHeader>
      <DialogTitle>{promptEditor === 'new' ? 'New persona' : 'Edit system prompt'}</DialogTitle>
      <DialogDescription>
        This text is prepended to the GSG harness introduction (policy, harness mechanics,
        context injection) automatically.
      </DialogDescription>
    </DialogHeader>
    <div class="space-y-3">
      <div class="space-y-1.5">
        <label class="text-xs font-medium text-muted-foreground" for="persona-name">Name</label>
        <input
          id="persona-name"
          bind:value={editorName}
          placeholder="e.g. Writer"
          class="h-9 w-full rounded-md border border-border bg-transparent px-3 text-sm outline-none transition focus:border-border"
        />
      </div>
      <div class="space-y-1.5">
        <label class="text-xs font-medium text-muted-foreground" for="persona-prompt">System prompt</label>
        <textarea
          id="persona-prompt"
          bind:value={editorPrompt}
          rows="12"
          placeholder="You are a writer…"
          class="w-full resize-y rounded-md border border-border bg-transparent px-3 py-2 font-mono text-xs leading-5 outline-none transition focus:border-border"
        ></textarea>
      </div>
    </div>
    <DialogFooter>
      <Button variant="ghost" onclick={() => (promptEditor = null)}>Cancel</Button>
      <Button disabled={editorBusy} onclick={() => void savePromptEditor()}>
        {#if editorBusy}
          <Loader2 class="size-4 animate-spin" />
          Saving…
        {:else}
          Save
        {/if}
      </Button>
    </DialogFooter>
  </DialogContent>
</Dialog>

<!-- namespace whitelist editor: one text input per item -->
<Dialog
  open={namespacesTarget !== null}
  onOpenChange={(open: boolean) => !open && (namespacesTarget = null)}
>
  <DialogContent>
    <DialogHeader>
      <DialogTitle>Edit allowed namespaces</DialogTitle>
      <DialogDescription>
        One namespace per item; this persona's current whitelist is
        {#if namespacesTarget}{namespacesText(namespacesTarget)}{:else}…{/if}. Leave the list empty
        for all namespaces served by the chat loop.
      </DialogDescription>
    </DialogHeader>
    <div class="space-y-2">
      {#each namespaceItems as item (item.id)}
        <div class="flex items-center gap-2">
          <input
            bind:value={item.value}
            placeholder="e.g. gsg"
            class="h-9 w-full rounded-md border border-border bg-transparent px-3 font-mono text-sm outline-none transition focus:border-border"
          />
          <button
            class="inline-flex size-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-destructive/10 hover:text-destructive focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
            title="remove namespace"
            onclick={() => (namespaceItems = namespaceItems.filter((row) => row.id !== item.id))}
          >
            <Trash2 class="size-4" />
          </button>
        </div>
      {/each}
      <Button
        variant="ghost"
        size="sm"
        onclick={() => (namespaceItems = [...namespaceItems, { id: namespaceRowId++, value: '' }])}
      >
        <Plus class="size-4" />
        Add namespace
      </Button>
    </div>
    <DialogFooter>
      <Button variant="ghost" onclick={() => (namespacesTarget = null)}>Cancel</Button>
      <Button disabled={namespacesBusy} onclick={() => void saveNamespacesEditor()}>
        {#if namespacesBusy}
          <Loader2 class="size-4 animate-spin" />
          Saving…
        {:else}
          Save
        {/if}
      </Button>
    </DialogFooter>
  </DialogContent>
</Dialog>

<!-- delete confirm -->
<Dialog open={deleteTarget !== null} onOpenChange={(open: boolean) => !open && (deleteTarget = null)}>
  <DialogContent>
    <DialogHeader>
      <div class="flex items-center gap-3">
        <div class="flex size-9 shrink-0 items-center justify-center rounded-full bg-destructive/15 text-destructive">
          <Trash2 class="size-4" />
        </div>
        <div class="min-w-0">
          <DialogTitle>Delete persona?</DialogTitle>
          <DialogDescription>
            "{deleteTarget?.name ?? ''}" will be deleted. Chats that used it fall back to the
            default persona on their next run.
          </DialogDescription>
        </div>
      </div>
    </DialogHeader>
    <DialogFooter>
      <Button variant="ghost" onclick={() => (deleteTarget = null)}>Cancel</Button>
      <Button variant="destructive" onclick={confirmDelete}>Delete</Button>
    </DialogFooter>
  </DialogContent>
</Dialog>
