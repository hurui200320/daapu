<script lang="ts">
  import type { Snippet } from 'svelte'
  import { cn } from '../../utils'

  type Props = { class?: string; children: Snippet; [key: string]: unknown }

  let { class: className, children, ...rest }: Props = $props()
</script>

<div class={cn('flex flex-col gap-1.5', className)} {...rest}>
  {@render children()}
</div>
