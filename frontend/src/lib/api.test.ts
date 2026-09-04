import { afterEach, describe, expect, it, vi } from 'vitest'
import { listChats, parseBlock, streamChat } from './api'
import type { StreamEvent } from './types'

/** Collect all events from a streamChat run. */
async function drain(gen: AsyncGenerator<StreamEvent>): Promise<StreamEvent[]> {
  const out: StreamEvent[] = []
  for await (const ev of gen) out.push(ev)
  return out
}

describe('streamChat (SSE wire protocol)', () => {
  afterEach(() => vi.unstubAllGlobals())

  function stubStream(chunks: string[]): void {
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        const enc = new TextEncoder()
        for (const c of chunks) controller.enqueue(enc.encode(c))
        controller.close()
      },
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, body }) as Response),
    )
  }

  it('parses LF-delimited event blocks in order', async () => {
    stubStream([
      'event: comment\ndata: connected\n\n',
      'event: text\ndata: {"delta":"he"}\n\n',
      'event: done\ndata: {}\n\n',
    ])
    const events = await drain(streamChat('c1', { text: 'x', model: 'm', personaId: 0 }))
    expect(events.map((e) => e.event)).toEqual(['comment', 'text', 'done'])
  })

  it('tolerates CRLF delimiters split across chunk boundaries', async () => {
    // a proxy that rewrites newlines splits \r\n pairs mid-chunk — the
    // normalizer must reconcile them after the accumulation
    stubStream([
      'event: text\r',
      '\ndata: {"de',
      'lta":"a"}\r\n\r',
      '\nevent: reasoning\r\ndata: {"delta":"b"}\r\n\r\n',
    ])
    const events = await drain(streamChat('c1', { text: 'x', model: 'm', personaId: 0 }))
    expect(events).toEqual([
      { event: 'text', data: '{"delta":"a"}' },
      { event: 'reasoning', data: '{"delta":"b"}' },
    ])
  })

  it('handles a block arriving fragmented across three reads', async () => {
    stubStream(['event: to', 'ol_call\ndata: {"name":', '"t","args":{}}\n', '\n'])
    const events = await drain(streamChat('c1', { text: 'x', model: 'm', personaId: 0 }))
    expect(events).toEqual([{ event: 'tool_call', data: '{"name":"t","args":{}}' }])
  })
})

describe('listChats (keyset pagination walk)', () => {
  afterEach(() => vi.unstubAllGlobals())

  /** Stub fetch to answer with the given JSON bodies in request order. */
  function stubPages(pages: unknown[]) {
    const queue = [...pages]
    const mock = vi.fn<(path: string) => Promise<Response>>(
      async () => ({ ok: true, json: async () => queue.shift() }) as Response,
    )
    vi.stubGlobal('fetch', mock)
    return mock
  }

  it('walks every page and flattens in order, encoding each cursor', async () => {
    // 'x y' needs URL-encoding: real chat ids (`$millis-$random`) never do,
    // so the space proves the walk encodes instead of assuming
    const fetchMock = stubPages([
      { chats: [{ id: 'b', title: 'B', personaId: 0 }], nextCursor: 'x y' },
      { chats: [{ id: 'a', title: 'A', personaId: 0 }], nextCursor: '0-9' },
      { chats: [{ id: '1-8', title: 'C', personaId: 0 }] }, // no nextCursor = last page
    ])
    expect(await listChats()).toEqual([
      { id: 'b', title: 'B', personaId: 0 },
      { id: 'a', title: 'A', personaId: 0 },
      { id: '1-8', title: 'C', personaId: 0 },
    ])
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([
      '/api/chats',
      '/api/chats?cursor=x%20y',
      '/api/chats?cursor=0-9',
    ])
  })

  it('stops on an empty page even when it carries a nextCursor', async () => {
    // a strict-inequality backend can never send this, but the walk must
    // not loop forever if one ever does (see the guard in listChats)
    const fetchMock = stubPages([
      { chats: [{ id: 'b', title: 'B', personaId: 0 }], nextCursor: 'x' },
      { chats: [], nextCursor: 'would-loop-forever' },
    ])
    expect(await listChats()).toEqual([{ id: 'b', title: 'B', personaId: 0 }])
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})

describe('parseBlock (SSE block parser)', () => {
  it('parses event + data lines', () => {
    expect(parseBlock('event: text\ndata: {"delta":"hi"}')).toEqual({
      event: 'text',
      data: '{"delta":"hi"}',
    })
  })

  it('defaults the event name to "message" when omitted', () => {
    expect(parseBlock('data: x')).toEqual({ event: 'message', data: 'x' })
  })

  it('strips exactly ONE leading space per data line per the SSE spec', () => {
    // significant leading whitespace in payloads must survive
    expect(parseBlock('data:  padded')).toEqual({ event: 'message', data: ' padded' })
    expect(parseBlock('data:no-space')).toEqual({ event: 'message', data: 'no-space' })
  })

  it('joins multi-line data with newlines', () => {
    expect(parseBlock('data: line1\ndata: line2')).toEqual({ event: 'message', data: 'line1\nline2' })
  })

  it('ignores comment lines (the backend pre-run "comment" event) and colon-only blocks', () => {
    expect(parseBlock(': connected')).toBeNull()
    expect(parseBlock('')).toBeNull()
    expect(parseBlock('\n')).toBeNull()
  })

  it('keeps unknown event names for forward compatibility', () => {
    // a future backend may add events; the store switch skips them by default
    expect(parseBlock('event: future_thing\ndata: {"x":1}')).toEqual({
      event: 'future_thing',
      data: '{"x":1}',
    })
  })
})
