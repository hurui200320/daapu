<script lang="ts">
  import ChatView from './lib/ChatView.svelte'
  import MemoriesView from './lib/MemoriesView.svelte'

  let view = $state<'chat' | 'memories'>('chat')
</script>

<div class="app">
  <header>
    <div class="brand">daapu</div>
    <nav>
      <button class="tab" class:active={view === 'chat'} onclick={() => (view = 'chat')}>chat</button>
      <button class="tab" class:active={view === 'memories'} onclick={() => (view = 'memories')}>memories</button>
    </nav>
  </header>
  <main>
    <!-- both views stay mounted so the chat view (messages, live stream)
         survives tab switches; visibility is CSS-only -->
    <div class="view" class:hidden={view !== 'chat'}><ChatView /></div>
    <div class="view" class:hidden={view !== 'memories'}><MemoriesView /></div>
  </main>
</div>

<style>
  .app {
    display: flex;
    flex-direction: column;
    height: 100vh;
  }

  header {
    display: flex;
    align-items: center;
    gap: 1rem;
    padding: 0.5rem 1rem;
    border-bottom: 1px solid var(--border);
  }

  .brand {
    font-weight: bold;
    letter-spacing: 0.05em;
  }

  nav {
    display: flex;
    gap: 0.3rem;
  }

  .tab {
    font: inherit;
    border: none;
    background: none;
    color: var(--text-muted);
    cursor: pointer;
    padding: 0.3rem 0.6rem;
    border-radius: 0.5rem;
  }

  .tab.active {
    color: var(--text);
    background: var(--input-bg);
  }

  main {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  .view {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  .hidden {
    display: none;
  }
</style>
