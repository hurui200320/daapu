<script lang="ts">
  import type { Snippet } from 'svelte'
  import { cn } from '../../utils'

  type Props = { class?: string; children: Snippet; [key: string]: unknown }

  let { class: className, children, ...rest }: Props = $props()
</script>

<h2 class={cn('text-lg font-semibold leading-none tracking-tight', className)} {...rest}>
  {@render children()}
</h2>
