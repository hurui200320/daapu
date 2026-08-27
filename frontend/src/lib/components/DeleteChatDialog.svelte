<script lang="ts">
  import { chatStore as store } from '../chat-store.svelte'
  import type { ChatInfo } from '../types'
  import ConfirmDialog from './ui/confirm-dialog.svelte'

  let { target, onClose }: { target: ChatInfo | null; onClose: () => void } = $props()

  function confirmDelete() {
    if (!target) return
    // fire-and-forget: the backend extracts memories from the history before
    // deleting, which can take minutes — close the dialog right away so the
    // user isn't stuck waiting. The chat stays locked read-only via
    // [store.deletingIds] until the backend confirms or fails (toast).
    void store.deleteChat(target.id)
    onClose()
  }
</script>

<ConfirmDialog open={target !== null} {onClose} title="Delete chat?" onConfirm={confirmDelete}>
  "{target?.title ?? ''}" will be permanently deleted. This cannot be undone.
</ConfirmDialog>
