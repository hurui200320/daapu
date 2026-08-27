<script lang="ts">
  import { ChevronDown, UserRound } from '@lucide/svelte'
  import { DropdownMenu } from 'bits-ui'
  import { chatStore as store } from '../chat-store.svelte'
  import { personaStore } from '../persona-store.svelte'
  import { dropdownChipTrigger, dropdownContentPanel, dropdownItemClass } from './ui/dropdown-styles'

  /**
   * Persona picker: selects the persona of the CURRENT chat (per-chat, like
   * the backend's persona record). The choice is a transient override that
   * the next send carries with the request; the chat's record follows once
   * the run stores. The code default persona is always an option, first.
   */
  const current = $derived(store.currentPersonaId)

  const personaName = $derived(
    // fall back to a label while the catalog is empty (initial load or a
    // down backend) — the raw id "0" must not show in the chip
    personaStore.personas.find((p) => p.id === current)?.name ?? 'persona',
  )
</script>

<DropdownMenu.Root>
  <DropdownMenu.Trigger
    disabled={store.streaming}
    class={dropdownChipTrigger('max-w-28', 'sm:max-w-44')}
    title="persona of this chat"
  >
    <UserRound class="size-3.5 shrink-0 text-muted-foreground" />
    <span class="min-w-0 truncate">{personaName}</span>
    <ChevronDown class="size-3.5 shrink-0 text-muted-foreground" />
  </DropdownMenu.Trigger>
  <DropdownMenu.Portal>
    <DropdownMenu.Content class={dropdownContentPanel('w-56')} align="end" sideOffset={6}>
      <div class="max-h-72 overflow-y-auto">
        {#each personaStore.personas as persona (persona.id)}
          <DropdownMenu.Item
            class={dropdownItemClass('justify-between')}
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
