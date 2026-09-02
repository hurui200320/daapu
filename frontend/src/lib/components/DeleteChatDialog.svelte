<script lang="ts">
  import { chatStore as store } from '../chat-store.svelte'
  import type { ChatInfo } from '../types'
  import ConfirmDialog from './ui/confirm-dialog.svelte'

  let { target, onClose }: { target: ChatInfo | null; onClose: () => void } = $props()

  function confirmDelete() {
    if (!target) return
    // fire-and-forget: the backend snapshots the history into its background
    // extraction queue and deletes the row right away, so the request is
    // fast — close the dialog immediately; a failure surfaces as a toast and
    // the chat simply stays in the list.
    void store.deleteChat(target.id)
    onClose()
  }
</script>

<ConfirmDialog open={target !== null} {onClose} title="Delete chat?" onConfirm={confirmDelete}>
  "{target?.title ?? ''}" will be permanently deleted. This cannot be undone.
</ConfirmDialog>
