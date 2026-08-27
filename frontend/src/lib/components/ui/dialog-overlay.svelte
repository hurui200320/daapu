<script lang="ts">
  import { Dialog as DialogPrimitive } from 'bits-ui'
  import { cn } from '../../utils'

  type Props = { class?: string; [key: string]: unknown }

  let { class: className, ...rest }: Props = $props()
</script>

<DialogPrimitive.Overlay {...rest} class={cn('fixed inset-0 z-50 bg-black/50', className)} />
