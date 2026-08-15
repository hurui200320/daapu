<script lang="ts">
  import Button from './ui/button.svelte'
  import { Dialog } from './ui/dialog.svelte'
  import DialogContent from './ui/dialog-content.svelte'
  import DialogDescription from './ui/dialog-description.svelte'
  import DialogFooter from './ui/dialog-footer.svelte'
  import DialogHeader from './ui/dialog-header.svelte'
  import DialogTitle from './ui/dialog-title.svelte'
  import { chatStore as store } from '../chat-store.svelte'
  import type { ChatInfo } from '../types'

  let { target, onClose }: { target: ChatInfo | null; onClose: () => void } = $props()

  let title = $state('')
  let localError = $state<string | null>(null)
  let input: HTMLInputElement

  $effect(() => {
    if (target) {
      title = target.title
      localError = null
    }
  })

  // focus the field when the dialog opens (the input is inside a portal)
  $effect(() => {
    if (target && input) input.focus()
  })

  async function save() {
    if (!target) return
    const trimmed = title.trim()
    if (!trimmed) {
      localError = 'Chat title must not be empty'
      return
    }
    localError = null
    await store.renameChat(target.id, trimmed)
    onClose()
  }
</script>

<Dialog open={target !== null} onOpenChange={(open: boolean) => !open && onClose()}>
  <DialogContent>
    <DialogHeader>
      <DialogTitle>Rename chat</DialogTitle>
      <DialogDescription>Give this conversation a memorable title.</DialogDescription>
    </DialogHeader>
    <input
      bind:this={input}
      bind:value={title}
      placeholder="Chat title"
      onkeydown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault()
          void save()
        }
      }}
      class="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm outline-none transition focus:border-ring focus:ring-2 focus:ring-ring/50"
    />
    {#if localError}<p class="text-sm text-destructive">{localError}</p>{/if}
    <DialogFooter>
      <Button variant="ghost" onclick={onClose}>Cancel</Button>
      <Button onclick={() => void save()}>Save</Button>
    </DialogFooter>
  </DialogContent>
</Dialog>
