<script lang="ts">
  import { ChevronDown, UserRound } from '@lucide/svelte'
  import { DropdownMenu } from 'bits-ui'
  import { chatStore as store } from '../chat-store.svelte'
  import { personaStore } from '../persona-store.svelte'

  /**
   * Persona picker: selects the persona of the CURRENT chat (per-chat, like
   * the backend's persona record). The choice is a transient override that
   * the next send carries with the request; the chat's record follows once
   * the run stores. The code default persona is always an option, first.
   */
  const current = $derived(store.currentPersonaId)

  const personaName = $derived(
    personaStore.personas.find((p) => p.id === current)?.name ?? current
  )
</script>

<DropdownMenu.Root>
  <DropdownMenu.Trigger
    disabled={store.streaming}
    class="inline-flex h-8 max-w-44 items-center gap-1.5 rounded-md bg-muted px-2 text-xs text-foreground transition-colors hover:bg-muted-foreground/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50"
    title="persona of this chat"
  >
    <UserRound class="size-3.5 shrink-0 text-muted-foreground" />
    <span class="truncate">{personaName}</span>
    <ChevronDown class="size-3.5 shrink-0 text-muted-foreground" />
  </DropdownMenu.Trigger>
  <DropdownMenu.Portal>
    <DropdownMenu.Content
      class="z-50 w-56 rounded-lg border border-border bg-popover p-1.5 text-popover-foreground shadow-md"
      align="end"
      sideOffset={6}
    >
      <div class="max-h-72 overflow-y-auto">
        {#each personaStore.personas as persona}
          <DropdownMenu.Item
            class="flex cursor-pointer items-center justify-between gap-2 rounded-md px-2 py-1.5 text-sm data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground"
            onSelect={() => (store.personaOverride = persona.id)}
          >
            <span class="truncate">{persona.name}</span>
            {#if persona.id === current}
              <span class="shrink-0 rounded bg-muted px-1.5 py-0.5 text-[0.65rem] text-muted-foreground">active</span>
            {/if}
          </DropdownMenu.Item>
        {:else}
          <div class="px-2 py-4 text-center text-xs text-muted-foreground">no personas found</div>
        {/each}
      </div>
    </DropdownMenu.Content>
  </DropdownMenu.Portal>
</DropdownMenu.Root>
