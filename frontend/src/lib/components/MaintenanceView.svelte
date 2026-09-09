<script lang="ts">
  import { getMaintenanceStatus, setMaintenance } from '../api'
  import { router } from '../router.svelte'
  import { toastStore } from '../toast-store.svelte'
  import Switch from './ui/switch.svelte'
  import { errMsg } from '../utils'

  // the server's flag, never optimistic: every toggle and every visit
  // re-reads the state, so a failed toggle or a concurrent flip (another
  // tab) cannot leave a stale switch
  let enabled = $state(false)
  let busy = $state(false)
  // the initial load's failure (an inline banner — the view's own content);
  // a toggle failure goes to the toast stack instead (an action, not content)
  let error = $state<string | null>(null)

  async function refresh() {
    try {
      enabled = (await getMaintenanceStatus()).enabled
      error = null
    } catch (e) {
      error = errMsg(e)
    }
  }

  async function toggle(next: boolean) {
    if (busy) return
    busy = true
    try {
      enabled = (await setMaintenance(next)).enabled
      error = null
    } catch (e) {
      toastStore.pushError(e)
    } finally {
      // re-read regardless of the toggle's outcome: a concurrent flip
      // (another tab) may have landed after this PUT, so only a fresh GET
      // mirrors the write that actually won — the echoed state above is
      // the fallback for a failed re-read. busy stays up through it, so
      // the switch cannot be flipped again mid-refresh.
      await refresh()
      busy = false
    }
  }

  // Fetch only while the view is visible (it stays mounted, CSS-hidden on
  // the other routes): every visit re-reads the flag — one cheap GET,
  // always fresh. No background polling: a concurrent flip (another tab)
  // self-corrects on the next visit or toggle, both of which re-read.
  $effect(() => {
    if (router.current.name !== 'maintenance') return
    void refresh()
  })
</script>

<div class="h-full overflow-y-auto">
  <div class="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 pb-8 pt-6">
    <div>
      <h1 class="text-lg font-semibold tracking-tight">Maintenance</h1>
      <p class="text-xs text-muted-foreground">
        Server-wide maintenance switches. The ELTM maintenance mode freezes the long-term memory's readers and writers
        for as long as it stays on.
      </p>
    </div>
    {#if error}
      <div class="rounded-lg border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
        {error}
      </div>
    {/if}
    <div class="rounded-xl border border-border/60 bg-muted/40 p-4">
      <div class="flex items-start justify-between gap-4 text-sm">
        <!-- the <label for>/id pair names the switch without making the
             long description its click target (a <label> wrapping both
             would toggle on any click into the text, including a
             drag-selection) -->
        <div>
          <label for="eltm-maintenance">ELTM maintenance mode</label>
          <p class="text-xs text-muted-foreground">
            While on, every operation that reads or writes the long-term memory is blocked with 503: sending chat
            messages, deleting chats, and the ELTM digest/import. Pending memory extractions pause until it is turned
            off again. Everything else — browsing chats and the memory, renaming, truncating, forking — keeps working.
          </p>
        </div>
        <!-- controlled, not bound: the switch mirrors the server's state,
             so a failed toggle (or a stale read) cannot leave a phantom
             on/off that the server never applied -->
        <Switch
          id="eltm-maintenance"
          checked={enabled}
          disabled={busy}
          onCheckedChange={(next) => void toggle(next)}
          class="-mt-0.5"
        />
      </div>
    </div>
  </div>
</div>
