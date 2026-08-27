<script lang="ts">
  import type { Snippet } from 'svelte'
  import { Loader2, Trash2 } from '@lucide/svelte'
  import Button from './button.svelte'
  import { Dialog } from './dialog.svelte'
  import DialogContent from './dialog-content.svelte'
  import DialogDescription from './dialog-description.svelte'
  import DialogFooter from './dialog-footer.svelte'
  import DialogHeader from './dialog-header.svelte'
  import DialogTitle from './dialog-title.svelte'

  /**
   * Shared destructive-confirmation scaffold (delete chat / truncate
   * messages / delete persona): destructive icon + title + rich description,
   * cancel/confirm footer. The body content is a snippet so callers keep
   * their own description markup (dynamic counts, quoted names). While
   * [busy], both buttons lock and the confirm shows a spinner.
   */
  let {
    open,
    onClose,
    title,
    children,
    confirmLabel = 'Delete',
    busy = false,
    onConfirm,
  }: {
    /** Controlled open state: false closes the dialog. */
    open: boolean
    onClose: () => void
    title: string
    children: Snippet
    confirmLabel?: string
    busy?: boolean
    onConfirm: () => void
  } = $props()
</script>

<Dialog {open} onOpenChange={(o: boolean) => !o && !busy && onClose()}>
  <DialogContent>
    <DialogHeader>
      <div class="flex items-center gap-3">
        <div class="flex size-9 shrink-0 items-center justify-center rounded-full bg-destructive/15 text-destructive">
          <Trash2 class="size-4" />
        </div>
        <div class="min-w-0">
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{@render children()}</DialogDescription>
        </div>
      </div>
    </DialogHeader>
    <DialogFooter>
      <Button variant="ghost" disabled={busy} onclick={onClose}>Cancel</Button>
      <Button variant="destructive" disabled={busy} onclick={onConfirm}>
        {#if busy}
          <Loader2 class="size-4 animate-spin" />
        {/if}
        {confirmLabel}
      </Button>
    </DialogFooter>
  </DialogContent>
</Dialog>
