<script lang="ts">
  import { chatStore as store } from '../chat-store.svelte'
  import ConfirmDialog from './ui/confirm-dialog.svelte'

  // the chat + index of the user message to truncate FROM (inclusive), or
  // null while the dialog is closed; the chat id is pinned when the dialog
  // opens, so a chat switch before the confirm cannot redirect the delete
  let { target, onClose }: { target: { chatId: string; index: number } | null; onClose: () => void } = $props()

  // everything from the target message to the end of the chat
  const removed = $derived(target == null ? 0 : Math.max(0, store.messages.length - target.index))

  // in-flight guard: indices go stale once a truncate lands, so the dialog
  // locks while its request runs (the store additionally serializes per chat)
  let busy = $state(false)

  async function confirmDelete() {
    if (target == null || busy) return
    busy = true
    try {
      // the store toasts both failure modes itself (an API error, or a
      // guarded no-op while another history edit is in flight): only a
      // real truncation closes the dialog — a no-op must stay open so the
      // user can retry instead of believing the messages were removed
      if (await store.truncateMessages(target.chatId, target.index)) onClose()
    } finally {
      busy = false
    }
  }
</script>

<ConfirmDialog
  open={target !== null}
  {onClose}
  title="Delete this message and everything after?"
  {busy}
  onConfirm={() => void confirmDelete()}
>
  {removed} message{removed === 1 ? '' : 's'} will be removed from this chat. Nothing is extracted into memories. This cannot
  be undone.
</ConfirmDialog>
