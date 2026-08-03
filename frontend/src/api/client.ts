import type { ChatInfo, ChatMessage, User } from './types'

/**
 * Thin wrapper around fetch for the backend JSON API.
 *
 * All endpoints are same-origin (`/api/*`); the Vite dev server proxies them to
 * the Ktor backend on port 8080. Session auth rides on the cookie set by
 * register/login, so `credentials: 'same-origin'` is always sent.
 */
async function parseErrorBody(response: Response, fallback: string): Promise<string> {
  try {
    const body = (await response.json()) as { error?: string }
    if (body.error) return body.error
  } catch {
    // Non-JSON error body; keep the fallback.
  }
  return fallback
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const response = await fetch(path, {
    ...init,
    headers,
    credentials: 'same-origin',
  })

  if (!response.ok) {
    throw new ApiError(
      response.status,
      await parseErrorBody(response, `request failed with status ${response.status}`),
    )
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401
}

export const api = {
  register(username: string, password: string): Promise<User> {
    return request('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    })
  },

  login(username: string, password: string): Promise<User> {
    return request('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    })
  },

  logout(): Promise<void> {
    return request('/api/auth/logout', { method: 'POST' })
  },

  me(signal?: AbortSignal): Promise<User> {
    return request('/api/auth/me', { signal })
  },

  listChats(): Promise<ChatInfo[]> {
    return request('/api/chats')
  },

  createChat(): Promise<{ id: number }> {
    return request('/api/chats', { method: 'POST' })
  },

  deleteChat(id: number): Promise<void> {
    return request(`/api/chats/${id}`, { method: 'DELETE' })
  },

  listMessages(chatId: number): Promise<ChatMessage[]> {
    return request(`/api/chats/${chatId}`)
  },

  /**
   * Send a message and stream the SSE reply.
   *
   * `onFrame` is called with the accumulated assistant text on every data
   * frame; `onDone` fires on the `[DONE]` sentinel. If the request fails
   * (network error, non-OK response, malformed frame, or an abort from the
   * returned stop function), `onError` fires instead. `onDone`/`onError` are
   * mutually exclusive and fire at most once. Returns a function that aborts
   * the request (used by the "stop" button).
   */
  sendMessage(
    chatId: number,
    content: string,
    onFrame: (accumulated: string) => void,
    onDone: () => void,
    onError: (error: unknown) => void,
  ): () => void {
    const controller = new AbortController()
    let finished = false
    const finish = (fn: () => void) => {
      if (!finished) {
        finished = true
        fn()
      }
    }
    void (async () => {
      try {
        const response = await fetch(`/api/chats/${chatId}/messages`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ content }),
          credentials: 'same-origin',
          signal: controller.signal,
        })
        if (!response.ok) {
          throw new ApiError(
            response.status,
            await parseErrorBody(response, `request failed with status ${response.status}`),
          )
        }
        if (!response.body) {
          throw new ApiError(response.status, 'no response body')
        }

        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''

        const handleFrame = (frame: string) => {
          if (!frame.startsWith('data: ')) return
          const payload = frame.slice(6).trim()
          if (payload === '[DONE]') {
            finish(onDone)
          } else {
            const message = JSON.parse(payload) as { content: string }
            onFrame(message.content)
          }
        }

        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })

          // SSE frames are separated by a blank line: "data: {...}\n\n".
          let boundary: number
          while ((boundary = buffer.indexOf('\n\n')) !== -1) {
            const frame = buffer.slice(0, boundary)
            buffer = buffer.slice(boundary + 2)
            handleFrame(frame)
          }
        }
        // A final frame may arrive without the trailing blank line; don't drop it.
        if (buffer.trim()) handleFrame(buffer)
        finish(onDone)
      } catch (error) {
        finish(() => onError(error))
      }
    })()

    return () => controller.abort()
  },
}
