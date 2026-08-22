<script lang="ts">
  import { onMount } from 'svelte'
  import { MODEL_STORAGE_KEY, chatStore } from './lib/chat-store.svelte'
  import ChatView from './lib/components/ChatView.svelte'
  import EltmView from './lib/components/EltmView.svelte'
  import Sidebar from './lib/components/Sidebar.svelte'
  import { chatHomePath, replaceRoute, router } from './lib/router.svelte'
  import { toastStore } from './lib/toast-store.svelte'

  onMount(() => {
    router.init()
    void chatStore.init()
  })

  // The URL owns the active view and the open chat: translate the route into
  // store state. Guarded against redundant picks, so store actions that
  // update state AND navigate (create/fork/delete) don't trigger a reload.
  // While a run streams, chat-route changes (back/forward, URL edit) are
  // ignored — the sidebar locks chat switches the same way, and the stream
  // renders into store.messages; reading `streaming` makes this effect
  // re-run when the run ends, so the pending route applies then.
  $effect(() => {
    const route = router.current
    if (route.name !== 'chat') return
    if (chatStore.streaming) return
    if (route.chatId === null) {
      if (chatStore.chatId !== '') chatStore.closeChat()
    } else if (chatStore.deletedChatIds.has(route.chatId)) {
      // the route points at a chat deleted this session — e.g. the history
      // entry left behind when the open chat was deleted from another view,
      // now reached via back/forward or a URL edit: the load would 404, so
      // neutralize the route instead of picking it (close whatever is open
      // and replace the dead entry with home)
      if (chatStore.chatId !== '') chatStore.closeChat()
      replaceRoute(chatHomePath())
    } else if (route.chatId !== chatStore.chatId) {
      chatStore.pickChat(route.chatId)
    }
  })

  // model picker persistence: restore the stored id once the catalog is
  // loaded (a stale id would render a blank picker and a confusing 400 on
  // send: fall back to the first model), then write every change back
  $effect(() => {
    if (chatStore.models.length > 0 && chatStore.selectedModel === '') {
      const stored = localStorage.getItem(MODEL_STORAGE_KEY)
      chatStore.selectedModel = chatStore.models.some((m) => m.id === stored)
        ? stored!
        : chatStore.models[0].id
    }
    if (chatStore.selectedModel) {
      localStorage.setItem(MODEL_STORAGE_KEY, chatStore.selectedModel)
    }
  })
</script>

<div class="flex h-dvh gap-2 p-2">
  <Sidebar />
  <main class="flex min-w-0 flex-1 flex-col">
    <!-- both views stay mounted so the chat view (messages, live stream,
         composer draft) survives tab switches; visibility is CSS-only,
         driven by the route -->
    <div class="flex min-h-0 flex-1 flex-col" class:hidden={router.current.name !== 'chat'}>
      <ChatView />
    </div>
    <div class="flex min-h-0 flex-1 flex-col" class:hidden={router.current.name !== 'eltm'}>
      <EltmView />
    </div>
  </main>
</div>

<!-- global error notifications (top-right stack; click to dismiss) -->
<div class="pointer-events-none fixed right-4 top-4 z-50 flex w-80 flex-col gap-2">
  {#each toastStore.toasts as toast (toast.id)}
    <button
      onclick={() => toastStore.dismiss(toast.id)}
      class="pointer-events-auto w-full break-words rounded-lg border border-destructive/50 bg-destructive/10 px-3 py-2 text-left text-sm text-destructive shadow-md backdrop-blur-xl"
    >
      {toast.message}
    </button>
  {/each}
</div>
