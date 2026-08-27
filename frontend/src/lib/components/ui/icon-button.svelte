<script lang="ts" module>
  import { cn } from '../../utils'

  /**
   * IconButton's visual recipe, exported for the rare site that needs the
   * same look on a non-button element (the sidebar rail's real links —
   * middle-click requires an <a>, which cannot BE an IconButton).
   */
  export function iconButtonClass(...extra: (string | false | undefined)[]) {
    return cn(
      'inline-flex size-8 items-center justify-center rounded-lg text-muted-foreground transition',
      'hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
      'disabled:pointer-events-none disabled:opacity-40',
      ...extra,
    )
  }
</script>

<script lang="ts">
  import type { Snippet } from 'svelte'

  /**
   * Square icon-only utility button, shared by the sidebar rails and the
   * persona row actions (previously the same long class string was repeated
   * per site). Pass `class` for per-site tweaks (destructive hover,
   * positioning); every other attribute forwards as-is.
   */
  let { class: className, children, ...rest }: { class?: string; children: Snippet; [key: string]: unknown } = $props()
</script>

<button {...rest} class={iconButtonClass(className)}>
  {@render children()}
</button>
