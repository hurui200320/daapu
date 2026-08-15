<script lang="ts">
  import { onMount } from 'svelte'
  import { MODEL_STORAGE_KEY, chatStore } from './lib/chat-store.svelte'
  import ChatView from './lib/components/ChatView.svelte'
  import MemoriesView from './lib/components/MemoriesView.svelte'
  import Sidebar from './lib/components/Sidebar.svelte'
  import { toastStore } from './lib/toast-store.svelte'

  let view = $state<'chat' | 'memories'>('chat')

  onMount(() => {
    void chatStore.init()
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
  <Sidebar {view} onNavigate={(v: 'chat' | 'memories') => (view = v)} />
  <main class="flex min-w-0 flex-1 flex-col">
    <!-- both views stay mounted so the chat view (messages, live stream)
         survives tab switches; visibility is CSS-only -->
    <div class="flex min-h-0 flex-1 flex-col" class:hidden={view !== 'chat'}>
      <ChatView />
    </div>
    <div class="flex min-h-0 flex-1 flex-col" class:hidden={view !== 'memories'}>
      <MemoriesView />
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
