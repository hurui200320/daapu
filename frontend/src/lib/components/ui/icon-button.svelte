<script lang="ts">
  import type { Snippet } from 'svelte'
  import { cn } from '../../utils'

  /**
   * Square icon-only utility button, shared by the sidebar rails and the
   * persona row actions (previously the same long class string was repeated
   * per site). Pass `class` for per-site tweaks (destructive hover,
   * positioning); every other attribute forwards as-is.
   */
  let { class: className, children, ...rest }: { class?: string; children: Snippet; [key: string]: unknown } = $props()
</script>

<button
  {...rest}
  class={cn(
    'inline-flex size-8 items-center justify-center rounded-lg text-muted-foreground transition',
    'hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
    'disabled:pointer-events-none disabled:opacity-40',
    className,
  )}
>
  {@render children()}
</button>
