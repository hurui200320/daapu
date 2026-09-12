<script lang="ts">
  import { Info, Loader2, FileUp } from '@lucide/svelte'
  import { getEltmReplayStatus, startEltmReplay } from '../api'
  import type { ChatMessage, EltmReplayStatus } from '../types'
  import { parseChatMessagesFile, parseReplayKnobs, type ReplayKnobs } from '../replay-transfer'
  import { onIntervalAndFocus } from '../resync'
  import { toastStore } from '../toast-store.svelte'
  import { errMsg } from '../utils'
  import Button from './ui/button.svelte'

  // The parent keeps this component MOUNTED even when another tab is
  // active (CSS-hidden — see EltmView.svelte, the DigestForm pattern):
  // the picked file and the knob draft survive switching tabs (and
  // chats) while a walk runs in the background.
  let {
    active,
    onsubmitted,
  }: {
    /**
     * True while the Replay tab is the visible one: it gates the
     * visit-time status read only. It does NOT gate the polling — while a
     * walk runs, the 2s cadence tracks it from any tab, so the walk's
     * terminal resync fires wherever the user happens to be.
     */
    active: boolean
    onsubmitted: () => Promise<void>
  } = $props()

  // ---- The picked file and the window knobs ----

  let fileInput = $state<HTMLInputElement | null>(null)
  // the parsed messages waiting on the Start button, plus the name for
  // the summary line
  let picked = $state<{ name: string; messages: ChatMessage[] } | null>(null)
  let compactionRounds = $state('8')
  let contextRounds = $state('3')
  let starting = $state(false)
  let error = $state<string | null>(null)

  // the parsed knob pair or the rule the input breaks (the server
  // re-validates — authority: EltmReplayService.kt validateReplayKnobs)
  let knobs = $derived(parseReplayKnobs(compactionRounds, contextRounds))

  function onPicked(input: HTMLInputElement) {
    const file = input.files?.[0]
    // reset so picking the same file again still fires the change event
    input.value = ''
    if (!file) return
    parseChatMessagesFile(file)
      .then((messages) => {
        picked = { name: file.name, messages }
        error = null
      })
      .catch((e) => {
        // a broken file clears the previous pick: Start must never
        // silently upload the OLD chat while the summary line shows
        // the name of the file the user just tried to pick
        picked = null
        toastStore.pushError(e)
      })
  }

  // ---- The job's status ----

  let status = $state<EltmReplayStatus | null>(null)

  async function refreshStatus() {
    let next: EltmReplayStatus
    try {
      next = await getEltmReplayStatus()
    } catch {
      // a failed read keeps the last snapshot on screen; the next
      // poll/visit retries
      return
    }
    const wasRunning = status?.state === 'running'
    status = next
    if (wasRunning && next.state !== 'running') {
      // the walk we watched reached a terminal state — the browse lists
      // resync through the parent so already-drained records show up
      // (later records keep arriving via the view's 30s cadence)
      await onsubmitted()
    }
  }

  // every visit of the visible tab re-reads the status (a fresh walk may
  // have been started from another tab); while a walk runs, a 2s +
  // focus cadence tracks it to its terminal state
  $effect(() => {
    if (!active) return
    void refreshStatus()
  })
  $effect(() => {
    if (status?.state !== 'running') return
    return onIntervalAndFocus(2_000, () => void refreshStatus())
  })

  async function start(knobValues: ReplayKnobs) {
    if (starting || !picked) return
    starting = true
    error = null
    try {
      // 202 + the walk's status: the memory work itself drains in the
      // background worker — "finished" below means every region queued
      const next = await startEltmReplay(picked.messages, knobValues.compactionRounds, knobValues.contextRounds)
      status = next
      // an instantaneous walk can already answer finished — key the toast
      // off the returned status, not the request's outcome
      toastStore.push(
        next.state === 'finished'
          ? `Replay finished: ${next.messagesTotal} messages walked, ${next.jobsQueued} region(s) queued for extraction`
          : `Replay started: ${picked.messages.length} messages walking through the compaction windows`,
      )
    } catch (e) {
      error = errMsg(e)
    } finally {
      starting = false
    }
  }
</script>

{#if error}
  <div class="break-words rounded-lg border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
    {error}
  </div>
{/if}

<!-- The notice: what the replay does and what it does NOT do (the
     memory work is asynchronous, and the store has no review step). -->
<div class="rounded-2xl border border-border/30 bg-muted/60 p-4 shadow-sm backdrop-blur-md">
  <div class="flex items-start gap-2">
    <Info class="mt-0.5 size-4 shrink-0 text-muted-foreground" />
    <div class="min-w-0 text-sm text-muted-foreground">
      <p>
        Replay walks an uploaded foreign chat (the neutral `.messages.json` format, byte-identical to what
        <code>GET /api/chats/&#123;id&#125;/chat</code> serves — e.g. the SillyTavern transformer's output) through the production
        compaction stage window by window, and every dropped region — running summary included — goes into the background
        memory-extraction queue: the extractor and the ELTM writer agent turn each region into memories, exactly like a compaction
        or a deleted chat.
      </p>
      <ul class="mt-2 list-disc space-y-1 pl-5">
        <li>
          Why not import-and-delete: a foreign chat can be far longer than the models' windows; the walk feeds the
          extractor window-sized batches instead
        </li>
        <li>
          "Finished" means every region is <em>queued</em> — the memories land in the background (minutes are normal) and
          appear on the Entities/Relationships tabs as they drain
        </li>
        <li>Blocked with 503 while ELTM maintenance mode is on; a second walk while one runs is refused with 409</li>
        <li>
          There is no review step: the extracted facts go straight into the diary (the store is add-only), so replay
          chats you trust
        </li>
      </ul>
    </div>
  </div>
</div>

<div class="rounded-2xl border border-border/30 bg-muted/60 p-4 shadow-sm backdrop-blur-md">
  <div class="flex flex-col gap-3 text-sm">
    <div class="flex flex-wrap items-center gap-3">
      <Button size="sm" disabled={starting || status?.state === 'running'} onclick={() => fileInput?.click()}>
        <FileUp class="size-4" />
        {picked ? 'Replace file' : 'Pick chat file'}
      </Button>
      <input
        bind:this={fileInput}
        type="file"
        accept=".json,application/json"
        class="hidden"
        onchange={(e) => onPicked(e.currentTarget)}
      />
      {#if picked}
        <span class="min-w-0 truncate text-xs text-muted-foreground">
          {picked.name} — {picked.messages.length} messages
        </span>
      {:else}
        <span class="text-xs text-muted-foreground">no file picked yet</span>
      {/if}
    </div>
    <div class="flex flex-wrap items-center gap-4 text-xs text-muted-foreground">
      <label class="flex items-center gap-2">
        compactionRounds
        <input
          type="number"
          min="2"
          class="w-20 rounded-md border border-border/30 bg-background/60 px-2 py-1 text-sm disabled:opacity-50"
          bind:value={compactionRounds}
          disabled={starting || status?.state === 'running'}
        />
      </label>
      <label class="flex items-center gap-2">
        contextRounds
        <input
          type="number"
          min="1"
          class="w-20 rounded-md border border-border/30 bg-background/60 px-2 py-1 text-sm disabled:opacity-50"
          bind:value={contextRounds}
          disabled={starting || status?.state === 'running'}
        />
      </label>
      <span> window knobs in user rounds (defaults 8/3) — lower them for chats with huge single messages </span>
    </div>
    {#if knobs.error}
      <p class="text-xs text-destructive">{knobs.error}</p>
    {/if}
    <div class="flex justify-end">
      <Button
        size="sm"
        disabled={starting || status?.state === 'running' || !picked || !knobs.knobs}
        onclick={() => knobs.knobs && void start(knobs.knobs)}
      >
        {#if starting || status?.state === 'running'}
          <Loader2 class="size-4 animate-spin" />
        {/if}
        {starting ? 'Starting…' : 'Start replay'}
      </Button>
    </div>
  </div>
</div>

{#if status}
  <div class="rounded-2xl border border-border/30 bg-muted/60 p-4 shadow-sm backdrop-blur-md">
    <div class="flex flex-col gap-1 text-sm">
      {#if status.state === 'running'}
        <p class="text-xs text-muted-foreground">
          A walk is running in the background — {status.windowsCompacted}
          {status.windowsCompacted === 1 ? 'window' : 'windows'} compacted, {status.jobsQueued}
          {status.jobsQueued === 1 ? 'job' : 'jobs'} queued so far.
        </p>
      {:else if status.state === 'finished'}
        <p class="text-xs text-muted-foreground">
          Last replay finished: {status.messagesTotal} messages walked, {status.windowsCompacted}
          {status.windowsCompacted === 1 ? 'window' : 'windows'} compacted, {status.jobsQueued} extraction
          {status.jobsQueued === 1 ? 'job' : 'jobs'} queued — the memories land in the background as the queue drains.
        </p>
      {:else if status.state === 'failed'}
        <p class="text-xs text-destructive">
          Last replay failed after {status.windowsCompacted}
          {status.windowsCompacted === 1 ? 'window' : 'windows'} compacted and {status.jobsQueued}
          extraction {status.jobsQueued === 1 ? 'job' : 'jobs'} queued: {status.error}. The regions already queued stay
          queued and drain normally — re-running the same file re-enqueues everything (the writer skips already-recorded
          content).
        </p>
      {:else}
        <p class="text-xs text-muted-foreground">No replay has run since the server started.</p>
      {/if}
    </div>
  </div>
{/if}
