<script lang="ts">
  import type { Snippet } from 'svelte'
  import { ChevronDown } from '@lucide/svelte'
  import { Collapsible } from 'bits-ui'

  /**
   * llama.cpp webui style collapsible block: icon + title + optional subtitle,
   * chevron on hover, shimmer title while streaming, left-border content.
   */
  type Props = {
    icon: typeof ChevronDown
    title: string
    subtitle?: string
    shimmer?: boolean
    open?: boolean
    onOpenChange?: (open: boolean) => void
    children: Snippet
    [key: string]: unknown
  }

  let { icon, title, subtitle = '', shimmer = false, open = $bindable(false), onOpenChange, children, ...rest }: Props = $props()

  /**
   * Wire the bits-ui Root's open state back into the (bindable) prop so the
   * trigger click actually opens the block: the content and chevron render
   * from `open`, and without the write-back a controlled Root would stay
   * closed no matter how often the trigger is clicked.
   */
  function handleOpenChange(value: boolean) {
    open = value
    onOpenChange?.(value)
  }
</script>

<Collapsible.Root
  {open}
  onOpenChange={handleOpenChange}
  class="group/collapsible my-1 w-full"
  {...rest}
>
  <Collapsible.Trigger class="flex w-full cursor-pointer items-start gap-2 rounded-md py-1.5 pr-1 text-left text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50">
    {#if icon}
      {@const Icon = icon}
      <Icon class="mt-1 size-4 shrink-0 text-muted-foreground/60" />
    {/if}
    <span class="text-sm font-medium {shimmer ? 'shimmer-text' : ''}">{title}</span>
    {#if subtitle}<span class="text-xs italic text-muted-foreground/70">{subtitle}</span>{/if}
    <ChevronDown
      class="ml-auto mt-0.5 size-4 shrink-0 opacity-0 transition-all group-hover/collapsible:opacity-100 no-hover:opacity-100 {open ? 'rotate-180' : ''}"
    />
  </Collapsible.Trigger>
  <Collapsible.Content>
    {#if open}
      <div class="my-2 min-w-0 border-l border-muted-foreground/20 pl-4 pr-2">{@render children()}</div>
    {/if}
  </Collapsible.Content>
</Collapsible.Root>
