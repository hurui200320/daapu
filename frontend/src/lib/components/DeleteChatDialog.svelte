<script lang="ts">
  import { Loader2, Trash2 } from '@lucide/svelte'
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

  // the backend extracts SSTM from the history before deleting, which can
  // take minutes: the confirm button is locked for the whole request
  const busy = $derived(target !== null && store.deletingIds.has(target.id))

  function confirmDelete() {
    if (!target || busy) return
    // fire-and-forget: the backend extracts SSTM from the history before
    // deleting, which can take minutes — close the dialog right away so the
    // user isn't stuck waiting. The chat stays locked read-only via
    // [store.deletingIds] until the backend confirms or fails (toast).
    store.deleteChat(target.id)
    onClose()
  }
</script>

<Dialog open={target !== null} onOpenChange={(open: boolean) => !open && onClose()}>
  <DialogContent>
    <DialogHeader>
      <div class="flex items-center gap-3">
        <div class="flex size-9 shrink-0 items-center justify-center rounded-full bg-destructive/15 text-destructive">
          <Trash2 class="size-4" />
        </div>
        <div class="min-w-0">
          <DialogTitle>Delete chat?</DialogTitle>
          <DialogDescription>
            "{target?.title ?? ''}" will be permanently deleted. This cannot be undone.
          </DialogDescription>
        </div>
      </div>
    </DialogHeader>
    <DialogFooter>
      <Button variant="ghost" onclick={onClose}>Cancel</Button>
      <Button variant="destructive" disabled={busy} onclick={confirmDelete}>
        {#if busy}
          <Loader2 class="size-4 animate-spin" />
          Deleting…
        {:else}
          Delete
        {/if}
      </Button>
    </DialogFooter>
  </DialogContent>
</Dialog>
