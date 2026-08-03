// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, isUnauthorized } from '../api/client'

const encoder = new TextEncoder()

function sseResponse(frames: string[]): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const frame of frames) {
        controller.enqueue(encoder.encode(frame))
      }
      controller.close()
    },
  })
  return new Response(stream, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('api request wrapper', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('sends credentials and parses JSON responses', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 1, username: 'alice' }))
    await expect(api.me()).resolves.toEqual({ id: 1, username: 'alice' })
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/auth/me')
    expect(init.credentials).toBe('same-origin')
  })

  it('sets a JSON content type only when a body is present', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 1, username: 'alice' }))
    await api.me()
    const [, getInit] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(getInit.headers).has('Content-Type')).toBe(false)

    fetchMock.mockResolvedValue(jsonResponse({ id: 1, username: 'alice' }))
    await api.login('alice', 'secret')
    const [, postInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(new Headers(postInit.headers).get('Content-Type')).toBe('application/json')
  })

  it('parses the error message from a JSON error body', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ error: 'bad username' }), { status: 400 }))
    await expect(api.login('alice', 'x')).rejects.toThrow('bad username')
  })

  it('falls back to the status message for a non-JSON error body', async () => {
    fetchMock.mockResolvedValue(new Response('boom', { status: 500 }))
    await expect(api.listChats()).rejects.toThrow('request failed with status 500')
  })

  it('resolves undefined for 204 responses', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    await expect(api.logout()).resolves.toBeUndefined()
  })

  it('isUnauthorized matches ApiError with status 401', () => {
    expect(isUnauthorized(new ApiError(401, 'not logged in'))).toBe(true)
    expect(isUnauthorized(new ApiError(403, 'forbidden'))).toBe(false)
    expect(isUnauthorized(new Error('nope'))).toBe(false)
  })
})

describe('api.sendMessage streaming', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts to the chat and streams frames until [DONE]', async () => {
    fetchMock.mockResolvedValue(
      sseResponse(['data: {"content":"Got it: hello"}\n\n', 'data: [DONE]\n\n']),
    )
    const onFrame = vi.fn()
    const onDone = vi.fn()
    const onError = vi.fn()

    api.sendMessage(1, 'hello', onFrame, onDone, onError)

    await vi.waitFor(() => {
      expect(onFrame).toHaveBeenCalledWith('Got it: hello')
      expect(onDone).toHaveBeenCalledTimes(1)
    })
    expect(onError).not.toHaveBeenCalled()

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/chats/1/messages')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ content: 'hello' })
  })

  it('fires onDone when the stream ends without the sentinel', async () => {
    fetchMock.mockResolvedValue(sseResponse(['data: {"content":"partial"}\n\n']))
    const onDone = vi.fn()
    const onError = vi.fn()

    api.sendMessage(1, 'hello', vi.fn(), onDone, onError)

    await vi.waitFor(() => expect(onDone).toHaveBeenCalledTimes(1))
    expect(onError).not.toHaveBeenCalled()
  })

  it('delivers a final frame that lacks the trailing blank line', async () => {
    fetchMock.mockResolvedValue(sseResponse(['data: {"content":"tail"}\n']))
    const onFrame = vi.fn()
    const onDone = vi.fn()
    const onError = vi.fn()

    api.sendMessage(1, 'hello', onFrame, onDone, onError)

    await vi.waitFor(() => {
      expect(onFrame).toHaveBeenCalledWith('tail')
      expect(onDone).toHaveBeenCalledTimes(1)
    })
    expect(onError).not.toHaveBeenCalled()
  })

  it('buffers frames that are split across network chunks', async () => {
    const bytes = encoder.encode(
      'data: {"content":"first"}\n\ndata: {"content":"second"}\n\ndata: [DONE]\n\n',
    )
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        // Chunk sizes deliberately cut across frame boundaries.
        for (let i = 0; i < bytes.length; i += 7) {
          controller.enqueue(bytes.slice(i, i + 7))
        }
        controller.close()
      },
    })
    fetchMock.mockResolvedValue(
      new Response(stream, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      }),
    )
    const onFrame = vi.fn()
    const onDone = vi.fn()
    const onError = vi.fn()

    api.sendMessage(1, 'hello', onFrame, onDone, onError)

    await vi.waitFor(() => {
      expect(onFrame).toHaveBeenNthCalledWith(1, 'first')
      expect(onFrame).toHaveBeenNthCalledWith(2, 'second')
      expect(onDone).toHaveBeenCalledTimes(1)
    })
    expect(onError).not.toHaveBeenCalled()
  })

  it('fires onError on a non-OK response', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ error: 'nope' }), { status: 500 }))
    const onDone = vi.fn()
    const onError = vi.fn()

    api.sendMessage(1, 'hello', vi.fn(), onDone, onError)

    await vi.waitFor(() => expect(onError).toHaveBeenCalled())
    expect(onDone).not.toHaveBeenCalled()
  })

  it('uses the error message from a JSON error body on a non-OK response', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ error: 'chat not found' }), { status: 404 }))
    const onDone = vi.fn()
    const onError = vi.fn()

    api.sendMessage(1, 'hello', vi.fn(), onDone, onError)

    await vi.waitFor(() => expect(onError).toHaveBeenCalled())
    expect(onDone).not.toHaveBeenCalled()
    expect((onError.mock.calls[0][0] as ApiError).message).toBe('chat not found')
  })

  it('fires onError on a malformed frame', async () => {
    fetchMock.mockResolvedValue(sseResponse(['data: {not json}\n\n']))
    const onDone = vi.fn()
    const onError = vi.fn()

    api.sendMessage(1, 'hello', vi.fn(), onDone, onError)

    await vi.waitFor(() => expect(onError).toHaveBeenCalled())
    expect(onDone).not.toHaveBeenCalled()
  })

  it('fires onError exactly once when the stream is aborted', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) => {
      const signal = init?.signal
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          signal?.addEventListener('abort', () => {
            controller.error(new Error('aborted'))
          })
        },
      })
      return Promise.resolve(
        new Response(stream, {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }),
      )
    })
    const onDone = vi.fn()
    const onError = vi.fn()

    const abort = api.sendMessage(1, 'hello', vi.fn(), onDone, onError)
    abort()

    await vi.waitFor(() => expect(onError).toHaveBeenCalledTimes(1))
    expect(onDone).not.toHaveBeenCalled()
  })
})
