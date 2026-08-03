import { act, render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../api/client'
import { ChatRoute, CatchAll, LoginRoute, RootLayout, SESSION_RESTORE_TIMEOUT_MS } from '../App'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      me: vi.fn(),
      logout: vi.fn(),
      listChats: vi.fn().mockResolvedValue([]),
      listMessages: vi.fn().mockResolvedValue([]),
      createChat: vi.fn(),
      deleteChat: vi.fn(),
      sendMessage: vi.fn(),
      login: vi.fn(),
      register: vi.fn(),
    },
  }
})

function renderAt(path: string) {
  const router = createMemoryRouter(
    [
      {
        path: '/',
        Component: RootLayout,
        children: [
          { index: true, Component: ChatRoute },
          { path: 'login', Component: LoginRoute },
          { path: '*', Component: CatchAll },
        ],
      },
    ],
    { initialEntries: [path] },
  )
  return render(<RouterProvider router={router} />)
}

describe('auth gating', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the login page when there is no session', async () => {
    vi.mocked(api.me).mockRejectedValue(new ApiError(401, 'not logged in'))
    renderAt('/')
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('restores a valid session and shows the chat page', async () => {
    vi.mocked(api.me).mockResolvedValue({ id: 1, username: 'alice' })
    renderAt('/')
    expect(await screen.findByText(/New chat/)).toBeInTheDocument()
    expect(screen.getByText('alice')).toBeInTheDocument()
  })

  it('redirects a logged-in user away from /login', async () => {
    vi.mocked(api.me).mockResolvedValue({ id: 1, username: 'alice' })
    renderAt('/login')
    expect(await screen.findByText(/New chat/)).toBeInTheDocument()
  })

  it('redirects unknown paths to login when logged out', async () => {
    vi.mocked(api.me).mockRejectedValue(new ApiError(401, 'not logged in'))
    renderAt('/nonsense')
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('redirects unknown paths to the chat page when logged in', async () => {
    vi.mocked(api.me).mockResolvedValue({ id: 1, username: 'alice' })
    renderAt('/nonsense')
    expect(await screen.findByText(/New chat/)).toBeInTheDocument()
  })

  it('stays logged out when session restore fails with a non-401 error', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(api.me).mockRejectedValue(new Error('network down'))
    renderAt('/')
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(consoleError).toHaveBeenCalled()
    consoleError.mockRestore()
  })

  it('gives up on a session restore that times out without logging an error', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.useFakeTimers()
    try {
      vi.mocked(api.me).mockImplementation(
        (signal?: AbortSignal) =>
          new Promise((_resolve, reject) => {
            if (signal?.aborted) {
              reject(new DOMException('The operation was aborted.', 'AbortError'))
            } else {
              signal?.addEventListener('abort', () =>
                reject(new DOMException('The operation was aborted.', 'AbortError')),
              )
            }
          }),
      )
      renderAt('/')
      await act(async () => {
        await vi.advanceTimersByTimeAsync(SESSION_RESTORE_TIMEOUT_MS)
      })
      expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
      expect(consoleError).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
      consoleError.mockRestore()
    }
  })
})
