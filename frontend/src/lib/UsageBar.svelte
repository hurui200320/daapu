<script lang="ts">
  /**
   * Context-usage indicator: the latest round's total tokens against the
   * currently selected model's context window, shown as numbers. Renders
   * nothing when either value is missing (empty chat, provider without
   * usage data, unknown model).
   */
  let { used, context }: { used: number | null; context: number | null } = $props()

  const pct = $derived(
    used != null && context != null && context > 0 ? Math.min(100, Math.round((used / context) * 100)) : null
  )

  const warn = $derived(pct != null && pct >= 80)
</script>

{#if pct != null && used != null && context != null}
  <span class="usage" class:warn title="context usage of the selected model">
    Usage: {used.toLocaleString()} / {context.toLocaleString()} tokens ({pct}%)
  </span>
{/if}

<style>
  .usage {
    font-size: 0.85rem;
    color: var(--text-muted);
    white-space: nowrap;
    flex-shrink: 0;
  }

  .warn {
    color: var(--danger-fg);
  }
</style>
