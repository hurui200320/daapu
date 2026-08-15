<script lang="ts">
  import type { Snippet } from 'svelte'
  import { cn } from '../../utils'

  type Props = { class?: string; children: Snippet; [key: string]: unknown }

  let { class: className, children, ...rest }: Props = $props()
</script>

<div class={cn('flex flex-col-reverse gap-2 sm:flex-row sm:justify-end', className)} {...rest}>
  {@render children()}
</div>
