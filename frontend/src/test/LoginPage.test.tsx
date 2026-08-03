import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../api/client'
import { LoginPage } from '../pages/LoginPage'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      login: vi.fn(),
      register: vi.fn(),
    },
  }
})

describe('LoginPage', () => {
  it('logs in with the trimmed username', async () => {
    vi.mocked(api.login).mockResolvedValue({ id: 1, username: 'alice' })
    const onLogin = vi.fn()

    render(<LoginPage onLogin={onLogin} />)

    await userEvent.type(screen.getByLabelText('Username'), '  alice  ')
    await userEvent.type(screen.getByLabelText('Password'), 'secret')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(onLogin).toHaveBeenCalledWith({ id: 1, username: 'alice' }))
    expect(api.login).toHaveBeenCalledWith('alice', 'secret')
  })

  it('registers when switched to register mode', async () => {
    vi.mocked(api.register).mockResolvedValue({ id: 2, username: 'bob' })
    const onLogin = vi.fn()

    render(<LoginPage onLogin={onLogin} />)

    await userEvent.click(screen.getByRole('button', { name: 'No account? Create one' }))
    expect(screen.getByRole('heading', { name: 'Create account' })).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Username'), 'bob')
    await userEvent.type(screen.getByLabelText('Password'), 'secret1')
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }))

    await waitFor(() => expect(onLogin).toHaveBeenCalledWith({ id: 2, username: 'bob' }))
    expect(api.register).toHaveBeenCalledWith('bob', 'secret1')
  })

  it('shows the server error message on failure', async () => {
    vi.mocked(api.login).mockRejectedValue(new ApiError(400, 'username taken'))
    const onLogin = vi.fn()

    render(<LoginPage onLogin={onLogin} />)

    await userEvent.type(screen.getByLabelText('Username'), 'alice')
    await userEvent.type(screen.getByLabelText('Password'), 'secret')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('username taken')).toBeInTheDocument()
    expect(onLogin).not.toHaveBeenCalled()
  })

  it('shows a generic message for unexpected errors', async () => {
    vi.mocked(api.login).mockRejectedValue(new Error('boom'))
    const onLogin = vi.fn()

    render(<LoginPage onLogin={onLogin} />)

    await userEvent.type(screen.getByLabelText('Username'), 'alice')
    await userEvent.type(screen.getByLabelText('Password'), 'secret')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Unexpected error, please try again')).toBeInTheDocument()
    expect(onLogin).not.toHaveBeenCalled()
  })

  it('locks the form while submitting', async () => {
    let resolveLogin: (user: { id: number; username: string }) => void
    vi.mocked(api.login).mockImplementation(
      () => new Promise((resolve) => (resolveLogin = resolve)),
    )
    const onLogin = vi.fn()

    render(<LoginPage onLogin={onLogin} />)

    await userEvent.type(screen.getByLabelText('Username'), 'alice')
    await userEvent.type(screen.getByLabelText('Password'), 'secret')
    const submit = screen.getByRole('button', { name: 'Sign in' })
    const switchButton = screen.getByRole('button', { name: 'No account? Create one' })
    await userEvent.click(submit)

    expect(submit).toBeDisabled()
    expect(switchButton).toBeDisabled()

    resolveLogin!({ id: 1, username: 'alice' })
    await waitFor(() => expect(onLogin).toHaveBeenCalled())
  })
})
