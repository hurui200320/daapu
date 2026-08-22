<script lang="ts">
  import { Bot, Loader2, MoreHorizontal, Network, PanelLeftClose, PanelLeftOpen, Pencil, Search, Sparkles, SquarePen, Trash2 } from '@lucide/svelte'
  import { DropdownMenu } from 'bits-ui'
  import { cn } from '../utils'
  import { chatStore as store } from '../chat-store.svelte'
  import { chatHref, router } from '../router.svelte'
  import type { ChatInfo } from '../types'
  import DeleteChatDialog from './DeleteChatDialog.svelte'
  import RenameChatDialog from './RenameChatDialog.svelte'
  import { buttonVariants } from './ui/button.svelte'

  let collapsed = $state(localStorage.getItem('daapu.sidebar-collapsed') === 'true')
  let query = $state('')
  let renameTarget = $state<ChatInfo | null>(null)
  let deleteTarget = $state<ChatInfo | null>(null)
  // in-flight title generations per chat id: concurrent generations on
  // different chats each keep their own spinner
  const titleGeneratingIds = $state<Set<string>>(new Set())

  $effect(() => {
    localStorage.setItem('daapu.sidebar-collapsed', String(collapsed))
  })

  // the URL owns navigation (router.svelte.ts): the active chat highlight
  // and the ELTM tab highlight both derive from the route. While a run
  // streams, the route may be stale (mid-run back/forward is ignored until
  // the run ends), so the streamed chat stays highlighted via the store
  const route = $derived(router.current)
  const activeChatId = $derived(
    store.streaming ? store.chatId : route.name === 'chat' ? route.chatId : null
  )

  const filtered = $derived(
    query.trim()
      ? store.knownChats.filter((c) => c.title.toLowerCase().includes(query.trim().toLowerCase()))
      : store.knownChats
  )

  const iconBtn =
    'inline-flex size-8 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-40'

  async function generateTitleFor(chat: ChatInfo) {
    titleGeneratingIds.add(chat.id)
    try {
      await store.generateTitle(chat.id)
    } finally {
      titleGeneratingIds.delete(chat.id)
    }
  }
</script>

<aside
  class="flex h-full shrink-0 flex-col overflow-hidden rounded-2xl border border-sidebar-border bg-sidebar/60 shadow-md backdrop-blur-xl transition-[width] duration-200 {collapsed ? 'w-12' : 'w-72'}"
>
  {#if collapsed}
    <div class="flex h-full flex-col items-center gap-1 py-3">
      <button title="expand sidebar" class={iconBtn} onclick={() => (collapsed = false)}>
        <PanelLeftOpen class="size-5" />
      </button>
      <button title="new chat" class={iconBtn} disabled={store.streaming} onclick={() => void store.createNewChat()}>
        <SquarePen class="size-5" />
      </button>
      <div class="flex-1"></div>
      <a
        title="eltm"
        href="#/eltm"
        class={cn(iconBtn, route.name === 'eltm' && 'bg-accent text-accent-foreground')}
      >
        <Network class="size-5" />
      </a>
    </div>
  {:else}
    <div class="flex items-center gap-2 px-3 py-3">
      <Bot class="size-5 shrink-0" />
      <span class="truncate text-sm font-semibold tracking-tight">daapu</span>
      <button title="collapse sidebar" class={cn(iconBtn, 'ml-auto')} onclick={() => (collapsed = true)}>
        <PanelLeftClose class="size-4" />
      </button>
    </div>
    <div class="space-y-2 px-2 pb-2">
      <button
        class={buttonVariants({ size: 'sm', class: 'w-full justify-start' })}
        disabled={store.streaming}
        onclick={() => void store.createNewChat()}
      >
        <SquarePen class="size-4" />
        New chat
      </button>
      <div class="relative">
        <Search class="absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
        <input
          bind:value={query}
          placeholder="Search conversations…"
          class="h-8 w-full rounded-md border border-transparent bg-transparent pl-8 pr-2 text-sm outline-none transition placeholder:text-muted-foreground hover:bg-foreground/10 focus:border-border"
        />
      </div>
    </div>
    <div class="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
      <div class="px-2 py-1.5 text-xs font-medium text-muted-foreground">Chats</div>
      {#if filtered.length === 0}
        <div class="px-2 py-6 text-center text-xs text-muted-foreground">
          {store.knownChats.length === 0 ? 'no chats yet' : 'no matches'}
        </div>
      {/if}
      {#each filtered as chat}
        <div
          class={cn(
            'group flex items-center rounded-lg transition-colors',
            chat.id === activeChatId ? 'bg-foreground/5' : 'hover:bg-foreground/10'
          )}
        >
          <!-- a real link: middle-click / open-in-new-tab work. While a run
               streams, the chat switch stays locked (pointer + keyboard):
               the stream's committed rounds render into store.messages.
               aria-hidden keeps the dead link out of the accessibility tree
               (aria-disabled is not valid on links) -->
          <a
            href={chatHref(chat.id)}
            class={cn(
              'min-w-0 flex-1 rounded-lg py-1.5 pl-2 pr-1 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
              store.streaming && 'pointer-events-none opacity-40'
            )}
            aria-hidden={store.streaming}
            tabindex={store.streaming ? -1 : undefined}
            title={chat.title}
          >
            <span class="block truncate text-sm font-medium">{chat.title}</span>
          </a>
          <DropdownMenu.Root>
            <DropdownMenu.Trigger
              disabled={store.streaming}
              class="mr-1 rounded-md p-1 text-muted-foreground opacity-0 transition hover:bg-foreground/10 hover:text-foreground focus-visible:opacity-100 group-hover:opacity-100 no-hover:opacity-100 disabled:pointer-events-none"
              title="chat actions"
            >
              <MoreHorizontal class="size-4" />
            </DropdownMenu.Trigger>
            <DropdownMenu.Portal>
              <DropdownMenu.Content
                class="z-50 min-w-40 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md"
                align="start"
                sideOffset={6}
              >
                <div class="border-b border-border px-2 py-1.5">
                  <span class="block max-w-64 break-words text-xs leading-snug text-muted-foreground">
                    {chat.title}
                  </span>
                </div>
                <DropdownMenu.Item
                  class="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground disabled:pointer-events-none disabled:opacity-40"
                  disabled={store.deletingIds.has(chat.id)}
                  onSelect={() => (renameTarget = chat)}
                >
                  <Pencil class="size-3.5" />
                  Rename
                </DropdownMenu.Item>
                <DropdownMenu.Item
                  class="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground disabled:pointer-events-none disabled:opacity-40"
                  disabled={titleGeneratingIds.has(chat.id) || store.deletingIds.has(chat.id)}
                  onSelect={() => void generateTitleFor(chat)}
                >
                  {#if titleGeneratingIds.has(chat.id)}
                    <Loader2 class="size-3.5 animate-spin" />
                  {:else}
                    <Sparkles class="size-3.5" />
                  {/if}
                  {titleGeneratingIds.has(chat.id) ? 'Generating…' : 'Generate title'}
                </DropdownMenu.Item>
                <DropdownMenu.Item
                  class="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm text-destructive data-[highlighted]:bg-destructive/10 data-[highlighted]:text-destructive disabled:pointer-events-none disabled:opacity-40"
                  disabled={store.deletingIds.has(chat.id)}
                  onSelect={() => (deleteTarget = chat)}
                >
                  <Trash2 class="size-3.5" />
                  Delete
                </DropdownMenu.Item>
              </DropdownMenu.Content>
            </DropdownMenu.Portal>
          </DropdownMenu.Root>
        </div>
      {/each}
    </div>
    <div class="border-t border-sidebar-border p-2">
      <a
        href="#/eltm"
        class={cn(
          buttonVariants({ variant: 'ghost', class: 'w-full justify-start' }),
          route.name === 'eltm' && 'bg-accent text-accent-foreground'
        )}
      >
        <Network class="size-4" />
        ELTM
      </a>
    </div>
  {/if}
</aside>

<RenameChatDialog target={renameTarget} onClose={() => (renameTarget = null)} />
<DeleteChatDialog target={deleteTarget} onClose={() => (deleteTarget = null)} />
