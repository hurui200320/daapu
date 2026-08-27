import type { ChatToolResultPart, StreamEvent } from './types'

/**
 * The chat run loop, extracted verbatim from ChatStore.send() so the
 * transport choreography can be unit-tested without the Svelte runtime.
 *
 * The session OWNS the event order and every branch (tool-result batching,
 * retry wipes, terminal done/error/abnormal-close recovery). The store-side
 * RunHost owns ALL reactive state — each verb below maps 1:1 onto a code
 * block of the original inline implementation. `run()` never throws: a
 * transport failure runs the same recovery sequence the original catch
 * clause ran, reported through [RunHost.toastTransportFailure] plus a
 * failed [RunOutcome].
 */

/** Parse a `{"delta": "..."}` SSE payload (text/reasoning events). */
export function parseDelta(data: string): string | null {
  try {
    const delta = (JSON.parse(data) as { delta?: unknown }).delta
    return typeof delta === 'string' ? delta : null
  } catch {
    return null
  }
}

/** Outcome summary for the caller's finally-block policy (banner vs toast). */
export interface RunOutcome {
  failed: boolean
}

/**
 * Reactive-state surface. Contract: every verb mutates display state only;
 * none may throw for normal wire shapes (malformed deltas are already
 * dropped by [parseDelta]; malformed tool payloads still throw — trusted
 * backend contract, surfaced as a run failure).
 */
export interface RunHost {
  /** A streamed delta arrived. Impl note: clears `retrying` first, like the original case bodies. */
  onTextDelta(delta: string): void
  onReasoningDelta(delta: string): void
  /** A live tool call appeared (the SSE payload carries no ids — display commits use id:''). */
  onToolCall(call: { name: string; args: Record<string, unknown> }): void
  /**
   * One tool result of the round arrived. Branching lives here (state-driven):
   * the FIRST result of a batch commits the assistant round before attaching;
   * subsequent ones extend the just-committed tool_result message.
   */
  onToolResult(part: ChatToolResultPart): void
  /** Legacy `retry` case verbatim: mark retrying, wipe partials, reopen blocks. */
  onRetryBegin(): void
  /**
   * Hide the live block for the DB resync (`runEnding = true`). Also clears
   * `retrying`: every legacy call site either did so explicitly (done/error)
   * or hid the block entirely (abnormal close), making the extra flip
   * unobservable there.
   */
  beginRunEnding(): void
  /**
   * Reset the live round's ENTIRE display state: the three partial buffers
   * plus the collapsible open-state flags (reasoning reopens, tool-call
   * blocks close) — every recovery path (retry wipe, done resync, error,
   * abnormal close) ends by clearing whatever the partials hold. Kept
   * separate from [commitFinalRound]: `done` commits BEFORE resetting.
   */
  resetLiveRound(): void
  /** `done` reload failed: commit the in-flight round so the answer isn't lost. */
  commitFinalRound(): void
  setRunError(message: string): void
  /** Truthy for ANY recorded run error (a falsy check, so '' suppresses the fallback toast too). */
  hasRunError(): boolean
  toastTransportFailure(e: unknown): void
}

/** External effectors (DB sync + the SSE stream itself), injectable for tests. */
export interface RunEnvironment {
  events: AsyncGenerator<StreamEvent>
  reloadFromDb(): Promise<boolean>
  resyncChats(): Promise<void>
}

export class StreamSession {
  constructor(
    private readonly env: RunEnvironment,
    private readonly host: RunHost,
  ) {}

  async run(): Promise<RunOutcome> {
    let failed = false
    let sawDone = false
    try {
      for await (const ev of this.env.events) {
        switch (ev.event) {
          case 'reasoning': {
            const delta = parseDelta(ev.data)
            if (delta != null) this.host.onReasoningDelta(delta)
            break
          }
          case 'text': {
            const delta = parseDelta(ev.data)
            if (delta != null) this.host.onTextDelta(delta)
            break
          }
          case 'tool_call':
            this.host.onToolCall(JSON.parse(ev.data))
            break
          case 'tool_result': {
            const result = JSON.parse(ev.data)
            this.host.onToolResult({
              type: 'tool_result',
              id: result.id,
              tool: result.name,
              isError: result.isError,
              // like parseDelta, a non-string payload degrades instead of
              // rendering "[object Object]" downstream
              parts: [{ type: 'text', text: typeof result.content === 'string' ? result.content : '' }],
            })
            break
          }
          case 'retry':
            // the server re-streams the whole round from scratch; drop the
            // failed round's partials so the retried output doesn't append to
            // stale text (committed rounds live in `messages` and survive)
            this.host.onRetryBegin()
            break
          case 'done': {
            sawDone = true
            // hide the live block for the resync: the run is over, and the
            // empty buffers would otherwise render a "Processing…" shimmer
            // while the DB reload + chat-list refresh are in flight
            this.host.beginRunEnding()
            if (!(await this.env.reloadFromDb())) {
              // the reload failed: commit the final round into the display
              // (the buffers hold the only copy of the answer) so the view
              // still shows the full run; the next resync or run replaces it
              this.host.commitFinalRound()
            }
            // The run's streaming state is stale in every path (the
            // display is the stored history now). Reset it so a future
            // render path can never resurrect the last run's partials.
            this.host.resetLiveRound()
            // The successful store also stamped the chat's persona record,
            // so refresh the chat list too — the picker would otherwise show
            // the pre-run record until the next 30s/focus resync.
            await this.env.resyncChats()
            break
          }
          case 'error': {
            failed = true
            // the failed round's partials must not flash under the error
            // banner while the reload below is in flight
            this.host.beginRunEnding()
            this.host.resetLiveRound()
            this.host.setRunError(this.runErrorMessage(ev.data))
            // the run failed before storing, so the optimistic user message
            // and any committed tool rounds are not in the DB: reload to match
            await this.env.reloadFromDb()
            break
          }
        }
      }
      if (!sawDone && !failed) {
        // the connection closed cleanly without a terminal event (e.g. the
        // server restarted mid-run): the run's outcome is unknown, so sync
        // with whatever the DB actually has and restore the draft
        failed = true
        this.host.beginRunEnding()
        this.host.resetLiveRound()
        await this.env.reloadFromDb()
        await this.env.resyncChats()
        this.host.setRunError('connection closed before the run completed')
      }
      return { failed }
    } catch (e) {
      failed = true
      // a fetch/parse failure may still have stored the run server-side (or
      // not): sync with the DB so a phantom optimistic message never sticks.
      // If the stream already surfaced a run error (streamError), that is
      // the more specific message — the banner or the finally fallback toast
      // carries it, so don't stack a second toast on top
      if (!this.host.hasRunError()) this.host.toastTransportFailure(e)
      this.host.beginRunEnding()
      await this.env.reloadFromDb()
      await this.env.resyncChats()
      return { failed }
    }
  }

  /**
   * The server sends `{"message": ...}` — anything else (a malformed payload,
   * a missing message field) falls back to the raw data instead of throwing
   * a SyntaxError up the stack. An EMPTY payload — or a blank `message`
   * field, which is just as useless — degrades to a generic message: a blank
   * error would render an empty banner and, via hasRunError's falsy check,
   * wrongly suppress the transport-failure toast on a later crash.
   */
  private runErrorMessage(data: string): string {
    try {
      const parsed = JSON.parse(data) as { message?: unknown }
      if (typeof parsed.message === 'string') {
        return parsed.message.length > 0 ? parsed.message : 'run failed'
      }
    } catch {
      // not JSON: fall through to the raw payload
    }
    return data.length > 0 ? data : 'run failed'
  }
}
