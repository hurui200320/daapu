<script lang="ts">
  import type { Snippet } from 'svelte'
  import { X } from '@lucide/svelte'
  import { Dialog as DialogPrimitive } from 'bits-ui'
  import { cn } from '../../utils'
  import DialogOverlay from './dialog-overlay.svelte'

  type Props = {
    class?: string
    children: Snippet
    [key: string]: unknown
  }

  let { class: className, children, ...rest }: Props = $props()
</script>

<DialogPrimitive.Portal>
  <DialogOverlay />
  <DialogPrimitive.Content
    {...rest}
    class={cn(
      // max-h + overflow: a dialog taller than the viewport (the persona
      // prompt editor on a phone, or any dialog with the on-screen keyboard
      // open) must scroll instead of clipping its footer out of reach
      'fixed left-1/2 top-1/2 z-50 grid max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 gap-4 overflow-y-auto rounded-lg border border-border bg-popover p-6 text-popover-foreground shadow-lg',
      className
    )}
  >
    {@render children()}
    <DialogPrimitive.Close
      class="absolute right-4 top-4 rounded-sm opacity-70 transition-opacity hover:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none"
    >
      <X class="size-4" />
      <span class="sr-only">Close</span>
    </DialogPrimitive.Close>
  </DialogPrimitive.Content>
</DialogPrimitive.Portal>
