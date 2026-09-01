<script lang="ts">
  import { ChevronDown, Package, Search } from '@lucide/svelte'
  import { DropdownMenu } from 'bits-ui'
  import { chatStore as store } from '../chat-store.svelte'
  import { dropdownChipTrigger, dropdownContentPanel, dropdownItemClass } from './ui/dropdown-styles'

  /**
   * Model picker: llama.cpp webui style chip trigger + searchable dropdown.
   * Selection lands in the shared store (persisted under `daapu.model` there).
   */
  let query = $state('')

  const filtered = $derived(
    query.trim() ? store.models.filter((m) => m.id.toLowerCase().includes(query.trim().toLowerCase())) : store.models,
  )

  // Model ids are `{provider.id}/{modelId}` (agent/model/LLM.kt) and the
  // provider id cannot contain a slash, so the first one splits provider
  // from model id; a slash-free id falls back to a single-line render.
  const splitModelId = (id: string): { provider: string; model: string } => {
    const slash = id.indexOf('/')
    return slash === -1 ? { provider: '', model: id } : { provider: id.slice(0, slash), model: id.slice(slash + 1) }
  }
</script>

<DropdownMenu.Root onOpenChange={(open: boolean) => open && (query = '')}>
  <DropdownMenu.Trigger
    disabled={store.streaming}
    class={dropdownChipTrigger('max-w-36', 'sm:max-w-52')}
    title={store.selectedModel || 'model'}
  >
    <Package class="size-3.5 shrink-0 text-muted-foreground" />
    <span class="min-w-0 truncate">{store.selectedModel || 'model'}</span>
    <ChevronDown class="size-3.5 shrink-0 text-muted-foreground" />
  </DropdownMenu.Trigger>
  <DropdownMenu.Portal>
    <DropdownMenu.Content class={dropdownContentPanel('w-80')} align="end" sideOffset={6}>
      <div class="relative mb-1">
        <Search class="absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
        <input
          bind:value={query}
          placeholder="Search models…"
          class="h-8 w-full rounded-md border border-transparent bg-muted pl-8 pr-2 text-sm outline-none transition placeholder:text-muted-foreground focus:border-border no-hover:text-base"
        />
      </div>
      <div class="max-h-72 overflow-y-auto">
        {#each filtered as model (model.id)}
          {@const parts = splitModelId(model.id)}
          <DropdownMenu.Item
            class={dropdownItemClass('justify-between', 'cursor-pointer')}
            onSelect={() => (store.selectedModel = model.id)}
            title={model.id}
          >
            <span class="flex min-w-0 flex-col">
              {#if parts.provider}
                <span class="text-[0.65rem] uppercase leading-tight tracking-wide text-muted-foreground">
                  {parts.provider}
                </span>
              {/if}
              <span class="truncate leading-tight">{parts.model}</span>
            </span>
            {#if model.vision}
              <span class="shrink-0 rounded bg-muted px-1.5 py-0.5 text-[0.65rem] text-muted-foreground">vision</span>
            {/if}
          </DropdownMenu.Item>
        {:else}
          <div class="px-2 py-4 text-center text-xs text-muted-foreground">no models found</div>
        {/each}
      </div>
    </DropdownMenu.Content>
  </DropdownMenu.Portal>
</DropdownMenu.Root>
