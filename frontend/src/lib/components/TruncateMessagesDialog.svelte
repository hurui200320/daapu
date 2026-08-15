<script lang="ts">
  import { Trash2 } from '@lucide/svelte'
  import Button from './ui/button.svelte'
  import { Dialog } from './ui/dialog.svelte'
  import DialogContent from './ui/dialog-content.svelte'
  import DialogDescription from './ui/dialog-description.svelte'
  import DialogFooter from './ui/dialog-footer.svelte'
  import DialogHeader from './ui/dialog-header.svelte'
  import DialogTitle from './ui/dialog-title.svelte'
  import { chatStore as store } from '../chat-store.svelte'

  // the chat + index of the user message to truncate FROM (inclusive), or
  // null while the dialog is closed; the chat id is pinned when the dialog
  // opens, so a chat switch before the confirm cannot redirect the delete
  let { target, onClose }: { target: { chatId: string; index: number } | null; onClose: () => void } = $props()

  // everything from the target message to the end of the chat
  const removed = $derived(
    target == null ? 0 : Math.max(0, store.messages.length - target.index)
  )

  function confirmDelete() {
    if (target == null) return
    // fast operation (no SSTM extraction): fire-and-forget, errors surface as
    // a toast; on success the store slices the message list locally
    store.truncateMessages(target.chatId, target.index)
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
          <DialogTitle>Delete this message and everything after?</DialogTitle>
          <DialogDescription>
            {removed} message{removed === 1 ? '' : 's'} will be removed from this chat.
            Nothing is extracted into memories. This cannot be undone.
          </DialogDescription>
        </div>
      </div>
    </DialogHeader>
    <DialogFooter>
      <Button variant="ghost" onclick={onClose}>Cancel</Button>
      <Button variant="destructive" onclick={confirmDelete}>Delete</Button>
    </DialogFooter>
  </DialogContent>
</Dialog>
