<script lang="ts">
  import { Loader2 } from '@lucide/svelte'
  import Button from './ui/button.svelte'
  import { Dialog } from './ui/dialog.svelte'
  import DialogContent from './ui/dialog-content.svelte'
  import DialogDescription from './ui/dialog-description.svelte'
  import DialogFooter from './ui/dialog-footer.svelte'
  import DialogHeader from './ui/dialog-header.svelte'
  import DialogTitle from './ui/dialog-title.svelte'

  /**
   * The ELTM import confirmation (see EltmView.svelte): shows the parsed
   * file's sizes and the one import decision — whether the file's attribute
   * values may overwrite existing keys (the backend's `overwriteAttr` query
   * param; the default merge keeps them). While [busy], both buttons lock
   * and the confirm shows a spinner.
   */
  let {
    open,
    onClose,
    entityCount,
    relationshipCount,
    busy = false,
    onConfirm,
  }: {
    /** Controlled open state: false closes the dialog. */
    open: boolean
    onClose: () => void
    entityCount: number
    relationshipCount: number
    busy?: boolean
    onConfirm: (overwriteAttr: boolean) => void
  } = $props()

  let overwriteAttr = $state(false)

  // every open starts with the conservative default (existing values kept)
  $effect(() => {
    if (open) overwriteAttr = false
  })
</script>

<Dialog {open} onOpenChange={(o: boolean) => !o && !busy && onClose()}>
  <DialogContent>
    <DialogHeader>
      <DialogTitle>Import ELTM</DialogTitle>
      <DialogDescription>
        Merge the file into the memory: {entityCount}
        {entityCount === 1 ? 'entity' : 'entities'} and
        {relationshipCount}
        {relationshipCount === 1 ? 'relationship' : 'relationships'}. Matching entries are found (notes dedup on date +
        text) and nothing is deleted.
      </DialogDescription>
    </DialogHeader>
    <label class="flex items-start gap-2 text-sm">
      <input type="checkbox" bind:checked={overwriteAttr} class="mt-1" />
      <span>
        Overwrite existing attribute values
        <span class="block text-xs text-muted-foreground">
          Off: keys the memory already has keep their stored value; only new keys are added. Diary notes and
          relationships merge either way.
        </span>
      </span>
    </label>
    <DialogFooter>
      <Button variant="ghost" disabled={busy} onclick={onClose}>Cancel</Button>
      <Button disabled={busy} onclick={() => onConfirm(overwriteAttr)}>
        {#if busy}
          <Loader2 class="size-4 animate-spin" />
        {/if}
        Import
      </Button>
    </DialogFooter>
  </DialogContent>
</Dialog>
