import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../api/client'
import { ChatPage } from '../pages/ChatPage'

const user = { id: 1, username: 'alice' }
const chats = [
  { id: 1, title: 'main chat', createdAt: '', updatedAt: '' },
  { id: 2, title: 'coding', createdAt: '', updatedAt: '' },
]

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      listChats: vi.fn(),
      createChat: vi.fn(),
      deleteChat: vi.fn(),
      listMessages: vi.fn(),
      sendMessage: vi.fn(),
      logout: vi.fn(),
    },
  }
})

describe('ChatPage chat list', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(api.listChats).mockResolvedValue(chats)
  })

  it('renders the chat list from the API', async () => {
    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    expect(await screen.findByText('main chat')).toBeInTheDocument()
    expect(screen.getByText('coding')).toBeInTheDocument()
  })

  it('opens a chat and loads its messages', async () => {
    vi.mocked(api.listMessages).mockResolvedValue([
      { id: 1, role: 'USER', content: 'hello', createdAt: '' },
      { id: 2, role: 'ASSISTANT', content: 'hi there', createdAt: '' },
    ])
    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))

    expect(api.listMessages).toHaveBeenCalledWith(1)
    expect(await screen.findByText('hello')).toBeInTheDocument()
    expect(await screen.findByText('hi there')).toBeInTheDocument()
  })

  it('streams a reply into the message list', async () => {
    const persisted: { id: number; role: 'USER' | 'ASSISTANT'; content: string; createdAt: string }[] = []
    vi.mocked(api.listMessages).mockImplementation(async () => [...persisted])
    vi.mocked(api.sendMessage).mockImplementation((_id, content, onFrame, onDone) => {
      persisted.push({ id: 1, role: 'USER', content, createdAt: '' })
      let accumulated = ''
      for (const word of `Got it: ${content}`.split(' ')) {
        accumulated += (accumulated ? ' ' : '') + word
        onFrame(accumulated)
      }
      persisted.push({ id: 2, role: 'ASSISTANT', content: accumulated, createdAt: '' })
      onDone()
      return () => {}
    })
    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    await userEvent.type(screen.getByPlaceholderText('Type a message…'), 'hi')
    await userEvent.click(screen.getByText('Send'))

    expect(api.sendMessage).toHaveBeenCalledWith(
      1,
      'hi',
      expect.any(Function),
      expect.any(Function),
      expect.any(Function),
    )
    expect(await screen.findByText('Got it: hi')).toBeInTheDocument()
  })

  it('logs out when the session expires while loading chats', async () => {
    vi.mocked(api.listChats).mockRejectedValue(new ApiError(401, 'not logged in'))
    const onLogout = vi.fn()

    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={onLogout} />
      </MemoryRouter>,
    )

    await waitFor(() => expect(onLogout).toHaveBeenCalled())
  })

  it('recovers when the stream errors', async () => {
    vi.mocked(api.listMessages).mockResolvedValue([])
    vi.mocked(api.sendMessage).mockImplementation((_id, _content, _onFrame, _onDone, onError) => {
      onError(new Error('network down'))
      return () => {}
    })
    const { container } = render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    await userEvent.type(screen.getByPlaceholderText('Type a message…'), 'hi')
    await userEvent.click(screen.getByText('Send'))

    expect(await screen.findByText('Send')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Type a message…')).toBeEnabled()
    expect(container.querySelectorAll('.message.assistant')).toHaveLength(0)
  })

  it('does not log out on a non-401 stream error', async () => {
    vi.mocked(api.listMessages).mockResolvedValue([])
    vi.mocked(api.sendMessage).mockImplementation((_id, _content, _onFrame, _onDone, onError) => {
      onError(new Error('network down'))
      return () => {}
    })
    const onLogout = vi.fn()

    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={onLogout} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    await userEvent.type(screen.getByPlaceholderText('Type a message…'), 'hi')
    await userEvent.click(screen.getByText('Send'))
    await screen.findByText('Send')

    expect(onLogout).not.toHaveBeenCalled()
  })

  it('lets the user send again after stopping a stream', async () => {
    vi.mocked(api.listMessages).mockResolvedValue([])
    let stopFn: (() => void) | null = null
    vi.mocked(api.sendMessage).mockImplementation(() => {
      stopFn = vi.fn()
      return stopFn
    })
    const { container } = render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    await userEvent.type(screen.getByPlaceholderText('Type a message…'), 'first')
    await userEvent.click(screen.getByText('Send'))

    expect(await screen.findByText('Stop')).toBeInTheDocument()
    await userEvent.click(screen.getByText('Stop'))
    expect(stopFn).toHaveBeenCalled()
    expect(container.querySelectorAll('.message.assistant')).toHaveLength(0)

    expect(await screen.findByText('Send')).toBeInTheDocument()
    await userEvent.type(screen.getByPlaceholderText('Type a message…'), 'second')
    await userEvent.click(screen.getByText('Send'))

    expect(screen.getByText('first')).toBeInTheDocument()
    expect(screen.getByText('second')).toBeInTheDocument()
    expect(container.querySelectorAll('.message.user')).toHaveLength(2)
  })

  it('applies only the most recent chat when switching quickly', async () => {
    let resolveFirst: (messages: import('../api/types').ChatMessage[]) => void
    vi.mocked(api.listMessages).mockImplementation((id: number) => {
      if (id === 1) {
        return new Promise((resolve) => {
          resolveFirst = resolve
        })
      }
      return Promise.resolve([{ id: 10, role: 'USER', content: 'from chat 2', createdAt: '' }])
    })
    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    await userEvent.click(screen.getByText('coding'))
    await screen.findByText('from chat 2')

    resolveFirst!([{ id: 5, role: 'USER', content: 'stale', createdAt: '' }])

    await waitFor(() => expect(screen.queryByText('stale')).not.toBeInTheDocument())
    expect(screen.getByText('from chat 2')).toBeInTheDocument()
  })

  it('ignores stale callbacks from an aborted request after a new stream starts', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(api.listMessages).mockResolvedValue([])
    let firstOnError: ((err: unknown) => void) | null = null
    const firstAbort = vi.fn()
    const secondAbort = vi.fn()
    vi.mocked(api.sendMessage).mockImplementation((_id, content, _onFrame, _onDone, onError) => {
      if (content === 'first') {
        firstOnError = onError
        return firstAbort
      }
      return secondAbort
    })
    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    await userEvent.type(screen.getByPlaceholderText('Type a message…'), 'first')
    await userEvent.click(screen.getByText('Send'))

    await userEvent.click(await screen.findByText('Stop'))
    expect(firstAbort).toHaveBeenCalled()

    // Start a second stream before the first request's error propagates.
    await userEvent.type(screen.getByPlaceholderText('Type a message…'), 'second')
    await userEvent.click(screen.getByText('Send'))
    expect(await screen.findByText('Stop')).toBeInTheDocument()

    // The stale abort error from the first request arrives now.
    firstOnError!(new DOMException('The user aborted a request.', 'AbortError'))

    expect(screen.getByText('Stop')).toBeInTheDocument()
    await userEvent.click(screen.getByText('Stop'))
    expect(secondAbort).toHaveBeenCalled()
    expect(consoleError).not.toHaveBeenCalled()
    consoleError.mockRestore()
  })

  it('clears the active chat when it is deleted', async () => {
    vi.mocked(api.listMessages).mockResolvedValue([{ id: 1, role: 'USER', content: 'hi', createdAt: '' }])
    vi.mocked(api.deleteChat).mockResolvedValue(undefined)
    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    await screen.findByText('hi')
    await userEvent.click(screen.getByRole('button', { name: 'Delete main chat' }))

    await waitFor(() => expect(api.deleteChat).toHaveBeenCalledWith(1))
    expect(screen.getByText('Select a chat or start a new chat.')).toBeInTheDocument()
  })

  it('does not log out on a non-401 chat list error', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(api.listChats).mockRejectedValue(new Error('boom'))
    const onLogout = vi.fn()

    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={onLogout} />
      </MemoryRouter>,
    )

    await waitFor(() => expect(consoleError).toHaveBeenCalled())
    expect(onLogout).not.toHaveBeenCalled()
    consoleError.mockRestore()
  })

  it('creates a new chat and opens it', async () => {
    vi.mocked(api.createChat).mockResolvedValue({ id: 3 })
    vi.mocked(api.listMessages).mockResolvedValue([])
    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByRole('button', { name: '+ New chat' }))

    expect(api.createChat).toHaveBeenCalled()
    expect(api.listMessages).toHaveBeenCalledWith(3)
    expect(screen.getByPlaceholderText('Type a message…')).toBeInTheDocument()
  })

  it('disables the composer while a stream is active', async () => {
    vi.mocked(api.listMessages).mockResolvedValue([])
    vi.mocked(api.sendMessage).mockImplementation(() => () => {})
    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    await userEvent.type(screen.getByPlaceholderText('Type a message…'), 'hi')
    await userEvent.click(screen.getByText('Send'))

    expect(api.sendMessage).toHaveBeenCalledTimes(1)
    expect(screen.getByPlaceholderText('Type a message…')).toBeDisabled()
    expect(screen.getByText('Stop')).toBeInTheDocument()
  })

  it('does not send a blank message', async () => {
    vi.mocked(api.listMessages).mockResolvedValue([])
    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    await userEvent.type(screen.getByPlaceholderText('Type a message…'), '   ')

    expect(screen.getByText('Send')).toBeDisabled()
    expect(api.sendMessage).not.toHaveBeenCalled()
  })

  it('sends the draft when Enter is pressed without Shift', async () => {
    vi.mocked(api.listMessages).mockResolvedValue([])
    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    await userEvent.type(screen.getByPlaceholderText('Type a message…'), 'hello{Enter}')

    expect(api.sendMessage).toHaveBeenCalledWith(
      1,
      'hello',
      expect.any(Function),
      expect.any(Function),
      expect.any(Function),
    )
  })

  it('inserts a newline on Shift+Enter instead of sending', async () => {
    vi.mocked(api.listMessages).mockResolvedValue([])
    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    await userEvent.type(screen.getByPlaceholderText('Type a message…'), 'hello')
    await userEvent.keyboard('{Shift>}{Enter}{/Shift}')

    expect(api.sendMessage).not.toHaveBeenCalled()
  })

  it('does not send while an IME composition is active', async () => {
    vi.mocked(api.listMessages).mockResolvedValue([])
    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    const textarea = screen.getByPlaceholderText('Type a message…')
    await userEvent.type(textarea, 'nihao')
    fireEvent.keyDown(textarea, { key: 'Enter', isComposing: true })

    expect(api.sendMessage).not.toHaveBeenCalled()
  })

  it('does not log out on a non-401 delete error', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(api.listMessages).mockResolvedValue([{ id: 1, role: 'USER', content: 'hi', createdAt: '' }])
    vi.mocked(api.deleteChat).mockRejectedValue(new Error('boom'))
    const onLogout = vi.fn()

    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={onLogout} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    await screen.findByText('hi')
    await userEvent.click(screen.getByRole('button', { name: 'Delete main chat' }))

    await waitFor(() => expect(consoleError).toHaveBeenCalled())
    expect(onLogout).not.toHaveBeenCalled()
    consoleError.mockRestore()
  })

  it('ignores a stale message load for a chat that was deleted', async () => {
    let resolveLoad: (messages: import('../api/types').ChatMessage[]) => void
    vi.mocked(api.listMessages).mockImplementation(
      () => new Promise((resolve) => (resolveLoad = resolve)),
    )
    vi.mocked(api.deleteChat).mockResolvedValue(undefined)
    render(
      <MemoryRouter>
        <ChatPage user={user} onLogout={() => {}} />
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByText('main chat'))
    await userEvent.click(screen.getByRole('button', { name: 'Delete main chat' }))
    await waitFor(() => expect(api.deleteChat).toHaveBeenCalled())

    resolveLoad!([{ id: 5, role: 'USER', content: 'stale', createdAt: '' }])

    await waitFor(() => expect(screen.queryByText('stale')).not.toBeInTheDocument())
    expect(screen.getByText('Select a chat or start a new chat.')).toBeInTheDocument()
  })
})
