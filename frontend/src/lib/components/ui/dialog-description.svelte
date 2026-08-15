<script lang="ts">
  import type { Snippet } from 'svelte'
  import { cn } from '../../utils'

  type Props = { class?: string; children: Snippet; [key: string]: unknown }

  let { class: className, children, ...rest }: Props = $props()
</script>

<p class={cn('text-sm text-muted-foreground', className)} {...rest}>
  {@render children()}
</p>
