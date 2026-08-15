<script lang="ts">
  import { onMount } from 'svelte'
  import { MODEL_STORAGE_KEY, chatStore } from './lib/chat-store.svelte'
  import ChatView from './lib/components/ChatView.svelte'
  import MemoriesView from './lib/components/MemoriesView.svelte'
  import Sidebar from './lib/components/Sidebar.svelte'

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
