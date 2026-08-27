import { describe, expect, it } from 'vitest'
import { parseDelta, StreamSession, type RunHost } from './stream-session'
import type { ChatToolResultPart, StreamEvent } from './types'

/** Scripted SSE generator: list of pre-parsed wire events. */
async function* script(...events: (StreamEvent | { throws: Error })[]): AsyncGenerator<StreamEvent> {
  for (const e of events) {
    if ('throws' in e) throw e.throws
    yield e
  }
}

const ev = {
  reasoning: (d: string): StreamEvent => ({ event: 'reasoning', data: JSON.stringify({ delta: d }) }),
  text: (d: string): StreamEvent => ({ event: 'text', data: JSON.stringify({ delta: d }) }),
  toolCall: (name: string): StreamEvent => ({
    event: 'tool_call',
    data: JSON.stringify({ name, args: { p: 1 } }),
  }),
  toolResult: (id: string, content = 'ok'): StreamEvent => ({
    event: 'tool_result',
    data: JSON.stringify({ id, name: 't', isError: false, content }),
  }),
  retry: (): StreamEvent => ({ event: 'retry', data: '{"message":"hiccup"}' }),
  done: (): StreamEvent => ({ event: 'done', data: '{}' }),
  error: (message: string): StreamEvent => ({
    event: 'error',
    data: JSON.stringify({ message, type: 'X' }),
  }),
}

type Recording = Record<keyof RunHost, unknown[][]>

function recordingHost(): { host: RunHost; log: Recording } {
  const log: Record<string, unknown[][]> = {}
  const mk = <K extends keyof RunHost>(key: K): RunHost[K] =>
    ((...args: unknown[]) => {
      ;(log[key] ??= []).push(args)
    }) as never
  return {
    log: log as Recording,
    host: {
      onTextDelta: mk('onTextDelta'),
      onReasoningDelta: mk('onReasoningDelta'),
      onToolCall: mk('onToolCall'),
      onToolResult: mk('onToolResult'),
      onRetryBegin: mk('onRetryBegin'),
      beginRunEnding: mk('beginRunEnding'),
      resetLiveRound: mk('resetLiveRound'),
      commitFinalRound: mk('commitFinalRound'),
      setRunError: mk('setRunError'),
      hasRunError: () => false,
      toastTransportFailure: mk('toastTransportFailure'),
    },
  }
}

/** Track hasRunError dynamically (an 'error' event must suppress later toasts). */
function statefulHost() {
  let runError: string | null = null
  const base = recordingHost()
  return {
    host: {
      ...base.host,
      setRunError(m: string) {
        runError = m
        base.host.setRunError(m)
      },
      hasRunError: () => runError != null,
    } as RunHost,
    log: base.log,
  }
}

describe('parseDelta', () => {
  it('extracts string deltas and drops malformed payloads', () => {
    expect(parseDelta('{"delta":"x"}')).toBe('x')
    expect(parseDelta('{}')).toBeNull()
    expect(parseDelta('{"delta":42}')).toBeNull()
    expect(parseDelta('not json')).toBeNull()
  })
})

describe('StreamSession', () => {
  function make(events: (StreamEvent | { throws: Error })[]) {
    let reloadOk = true
    const reloads: number[] = []
    const resyncs: number[] = []
    const env = {
      events: script(...events),
      reloadFromDb: async () => {
        reloads.push(reloads.length)
        return reloadOk
      },
      resyncChats: async () => {
        resyncs.push(resyncs.length)
      },
    }
    const rh = statefulHost()
    return {
      session: new StreamSession(env, rh.host),
      rh,
      reloads,
      resyncs,
      failReloadOnce: () => (reloadOk = false),
    }
  }

  it('streams deltas into the buffers and completes cleanly', async () => {
    const t = make([ev.text('a'), ev.reasoning('b'), ev.done()])
    await expect(t.session.run()).resolves.toEqual({ failed: false })
    expect(t.rh.log.onTextDelta).toEqual([['a']])
    expect(t.rh.log.onReasoningDelta).toEqual([['b']])
    expect(t.rh.log.beginRunEnding).toHaveLength(1)
    // successful reload → no final-round commit fallback, single resync
    expect(t.rh.log.commitFinalRound).toBeUndefined()
    expect(t.resyncs).toHaveLength(1)
    expect(t.reloads).toHaveLength(1)
  })

  it('parses tool payloads into the stored part shape (one call per result)', async () => {
    const t = make([
      ev.text('running…'),
      ev.toolCall('fs__read_text_file'),
      ev.toolCall('fs__list_directory'),
      ev.toolResult('r1'),
      ev.toolResult('r2', 'second'),
    ])
    await t.session.run()
    expect(t.rh.log.onToolCall).toEqual([
      [{ name: 'fs__read_text_file', args: { p: 1 } }],
      [{ name: 'fs__list_directory', args: { p: 1 } }],
    ])
    const parts = t.rh.log.onToolResult as unknown as [ChatToolResultPart[]]
    expect(parts).toEqual([
      [{ type: 'tool_result', id: 'r1', tool: 't', isError: false, parts: [{ type: 'text', text: 'ok' }] }],
      [
        {
          type: 'tool_result',
          id: 'r2',
          tool: 't',
          isError: false,
          parts: [{ type: 'text', text: 'second' }],
        },
      ],
    ])
  })

  it('drops malformed deltas without killing the run', async () => {
    const t = make([{ event: 'text', data: 'garbage' }, { event: 'text', data: '{}' }, ev.text('ok'), ev.done()])
    await expect(t.session.run()).resolves.toEqual({ failed: false })
    expect((t.rh.log.onTextDelta ?? []).map(([d]) => d)).toEqual(['ok'])
  })

  it('retries pass through to the host verb between delta batches', async () => {
    const t = make([ev.text('half'), ev.retry(), ev.text('fresh'), ev.done()])
    await t.session.run()
    expect(t.rh.log.onRetryBegin).toHaveLength(1)
    expect((t.rh.log.onTextDelta ?? []).map(([d]) => d)).toEqual(['half', 'fresh'])
  })

  it('commits the final round only when the done-reload fails, before wiping buffers', async () => {
    const t = make([ev.text('the answer'), ev.done()])
    t.failReloadOnce()
    await expect(t.session.run()).resolves.toEqual({ failed: false })
    expect(t.rh.log.commitFinalRound).toHaveLength(1)
  })

  it('surfaces terminal errors verbatim, wipes, then reloads (failed outcome)', async () => {
    const t = make([ev.text('partial'), ev.error('gateway exploded')])
    await expect(t.session.run()).resolves.toEqual({ failed: true })
    expect(t.rh.log.setRunError).toEqual([['gateway exploded']])
    expect(t.rh.log.resetLiveRound).toHaveLength(1)
    expect(t.resyncs).toHaveLength(0) // the error path does not refresh the chat list
    expect(t.reloads).toHaveLength(1)
  })

  it('falls back to the raw payload for malformed error bodies', async () => {
    const t = make([{ event: 'error', data: '"just a string"' }])
    await t.session.run()
    expect(t.rh.log.setRunError).toEqual([['"just a string"']])
  })

  it('reports abnormal connection close as a failed run', async () => {
    const t = make([ev.text('partial only')])
    await expect(t.session.run()).resolves.toEqual({ failed: true })
    expect(t.rh.log.setRunError).toEqual([['connection closed before the run completed']])
    expect(t.resyncs).toHaveLength(1)
  })

  it('converts a thrown transport failure into recovery without a redundant toast after a run error', async () => {
    const t = make([ev.error('real cause'), { throws: new Error('socket died') }])
    await expect(t.session.run()).resolves.toEqual({ failed: true })
    expect(t.rh.log.toastTransportFailure).toBeUndefined()
    expect(t.rh.log.setRunError).toEqual([['real cause']])
    expect(t.resyncs).toHaveLength(1)
  })

  it('toasts a bare transport failure (no prior run error)', async () => {
    const boom = new Error('fetch blew up')
    const t = make([ev.text('partial'), { throws: boom }])
    await expect(t.session.run()).resolves.toEqual({ failed: true })
    expect(t.rh.log.toastTransportFailure).toEqual([[boom]])
  })

  it('recovers from a malformed tool payload as a transport failure', async () => {
    // unlike deltas, tool payloads are parsed OUTSIDE the host: the thrown
    // SyntaxError takes the catch-path recovery (toast + reload + resync)
    const t = make([ev.text('partial'), { event: 'tool_call', data: 'not json' }])
    await expect(t.session.run()).resolves.toEqual({ failed: true })
    // the malformed event never reached the host verb...
    expect(t.rh.log.onToolCall).toBeUndefined()
    // ...but the full recovery sequence ran, toasting the parse error itself
    expect(t.rh.log.toastTransportFailure).toHaveLength(1)
    expect((t.rh.log.toastTransportFailure as unknown[][])[0][0]).toBeInstanceOf(SyntaxError)
    expect(t.rh.log.beginRunEnding).toHaveLength(1)
    // the catch path relies on the DB reload to replace the display; like
    // the legacy implementation it does NOT touch the live-round state
    expect(t.rh.log.resetLiveRound).toBeUndefined()
  })

  it('degrades a non-string tool_result content to an empty text part', async () => {
    // every other wire payload degrades gracefully; a structured/missing
    // content must not render "[object Object]" or undefined downstream
    const t = make([
      {
        event: 'tool_result',
        data: JSON.stringify({ id: 'r1', name: 't', isError: false, content: { nested: true } }),
      },
      { event: 'tool_result', data: JSON.stringify({ id: 'r2', name: 't', isError: false }) },
      ev.done(),
    ])
    await expect(t.session.run()).resolves.toEqual({ failed: false })
    const parts = t.rh.log.onToolResult as unknown as [ChatToolResultPart[]]
    expect(parts).toEqual([
      [{ type: 'tool_result', id: 'r1', tool: 't', isError: false, parts: [{ type: 'text', text: '' }] }],
      [{ type: 'tool_result', id: 'r2', tool: 't', isError: false, parts: [{ type: 'text', text: '' }] }],
    ])
  })

  it('ignores unknown event kinds (forward compatibility)', async () => {
    const t = make([{ event: 'comment', data: 'connected' }, { event: 'future_thing', data: '{}' }, ev.done()])
    await expect(t.session.run()).resolves.toEqual({ failed: false })
    expect(t.rh.log.commitFinalRound).toBeUndefined()
  })
})
