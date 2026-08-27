<script lang="ts">
  import { onMount } from 'svelte'
  import { PanelLeftOpen } from '@lucide/svelte'
  import { MODEL_STORAGE_KEY, chatStore } from './lib/chat-store.svelte'
  import { personaStore } from './lib/persona-store.svelte'
  import ChatView from './lib/components/ChatView.svelte'
  import EltmView from './lib/components/EltmView.svelte'
  import PersonaView from './lib/components/PersonaView.svelte'
  import Sidebar from './lib/components/Sidebar.svelte'
  import IconButton from './lib/components/ui/icon-button.svelte'
  import { chatHomePath, replaceRoute, router } from './lib/router.svelte'
  import { toastStore } from './lib/toast-store.svelte'
  import { uiStore } from './lib/ui-store.svelte'

  let kbInset = $state(0)
  onMount(() => {
    router.init()
    uiStore.init()
    void chatStore.init()
    void personaStore.init()

    // on-screen keyboard vs. the fixed h-dvh shell: the layout viewport does
    // not shrink for the keyboard and the document has no scroll range, so the
    // composer would sit behind it. `interactive-widget=resizes-content`
    // (index.html) fixes browsers that honor it by shrinking the layout
    // viewport; for the rest (iOS Safari) mirror the keyboard inset as a
    // bottom padding on the shell. Where the meta is honored innerHeight
    // already shrank, so the inset computes to 0 and this is a no-op.
    const vv = window.visualViewport
    if (!vv) return
    const update = () => {
      kbInset = Math.max(0, Math.round(window.innerHeight - vv.height - vv.offsetTop))
    }
    vv.addEventListener('resize', update)
    vv.addEventListener('scroll', update)
    return () => {
      vv.removeEventListener('resize', update)
      vv.removeEventListener('scroll', update)
    }
  })

  // the mobile navigation drawer closes on every navigation (a chat pick, a
  // personas/ELTM link, back/forward); a tap that changes NO route (the
  // already-open chat/personas/eltm link, or a streaming-locked chat link)
  // gets no effect run, so the sidebar closes the drawer itself there
  $effect(() => {
    void router.current
    uiStore.mobileNavOpen = false
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
      chatStore.selectedModel = chatStore.models.some((m) => m.id === stored) ? stored! : chatStore.models[0].id
    }
    if (chatStore.selectedModel) {
      localStorage.setItem(MODEL_STORAGE_KEY, chatStore.selectedModel)
    }
  })
</script>

<!-- overflow-x-hidden: nothing may ever push the fixed-height shell sideways
     (e.g. the composer's control row on a very narrow screen) -->
<div class="flex h-dvh gap-2 overflow-x-hidden p-2" style:padding-bottom={kbInset > 0 ? `${kbInset + 8}px` : undefined}>
  <Sidebar />
  <main class="flex min-w-0 flex-1 flex-col">
    <!-- mobile top bar: the sidebar is an overlay drawer below md, so every
         view needs the button that opens it (there is no other way back to
         the chat list from the ELTM/personas views) -->
    <div class="flex items-center pb-2 md:hidden">
      <IconButton title="open sidebar" aria-label="open sidebar" onclick={() => (uiStore.mobileNavOpen = true)}>
        <PanelLeftOpen class="size-5" />
      </IconButton>
    </div>
    <!-- all views stay mounted so the chat view (messages, live stream,
         composer draft) survives tab switches; visibility is CSS-only,
         driven by the route -->
    <div class="flex min-h-0 flex-1 flex-col" class:hidden={router.current.name !== 'chat'}>
      <ChatView />
    </div>
    <div class="flex min-h-0 flex-1 flex-col" class:hidden={router.current.name !== 'eltm'}>
      <EltmView />
    </div>
    <div class="flex min-h-0 flex-1 flex-col" class:hidden={router.current.name !== 'personas'}>
      <PersonaView />
    </div>
  </main>
</div>

<!-- mobile drawer scrim: tap outside the drawer to close it -->
{#if uiStore.mobileNavOpen}
  <button
    aria-label="close sidebar"
    class="fixed inset-0 z-30 bg-black/50 md:hidden"
    onclick={() => (uiStore.mobileNavOpen = false)}
  ></button>
{/if}

<!-- global notifications (top-right stack; click to dismiss; full-width on
     phones where a fixed 20rem box would crowd the screen edge) -->
<div
  class="pointer-events-none fixed right-4 top-4 z-50 flex w-80 flex-col gap-2 max-sm:left-4 max-sm:w-auto"
  role="status"
  aria-live="polite"
>
  {#each toastStore.toasts as toast (toast.id)}
    <button
      onclick={() => toastStore.dismiss(toast.id)}
      class="pointer-events-auto w-full break-words rounded-lg border px-3 py-2 text-left text-sm shadow-md backdrop-blur-xl {toast.kind ===
      'error'
        ? 'border-destructive/50 bg-destructive/10 text-destructive'
        : 'border-border/50 bg-muted/80 text-foreground'}"
    >
      {toast.message}
    </button>
  {/each}
</div>
