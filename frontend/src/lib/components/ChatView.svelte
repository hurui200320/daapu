<script lang="ts">
  import { chatStore as store } from '../chat-store.svelte'
  import Composer from './Composer.svelte'
  import MessageList from './MessageList.svelte'

  const showList = $derived(store.chatId !== '' && (store.messages.length > 0 || store.streaming))
</script>

<div class="relative flex h-full min-h-0 flex-col">
  {#if !store.chatId}
    <div class="flex flex-1 flex-col items-center justify-center gap-1.5 px-4">
      <h1 class="text-2xl font-semibold tracking-tight">Hello there</h1>
      <p class="text-sm text-muted-foreground">Select a conversation or start a new one</p>
    </div>
  {:else if !showList}
    <div class="flex flex-1 items-center justify-center px-4">
      <p class="text-sm text-muted-foreground">No messages yet — say hello</p>
    </div>
  {:else}
    <div class="min-h-0 flex-1">
      <MessageList />
    </div>
  {/if}
  {#if store.streamError}
    <div class="mx-auto w-full max-w-3xl space-y-2 px-4 pb-2">
      <div class="break-words rounded-lg border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
        run failed: {store.streamError}
      </div>
    </div>
  {/if}
  <Composer />
</div>
