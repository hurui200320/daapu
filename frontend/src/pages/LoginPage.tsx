import { useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, api } from '../api/client'
import type { User } from '../api/types'

interface LoginPageProps {
  onLogin: (user: User) => void
}

export function LoginPage({ onLogin }: LoginPageProps) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      const trimmed = username.trim()
      const user = mode === 'login' ? await api.login(trimmed, password) : await api.register(trimmed, password)
      onLogin(user)
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message)
      } else {
        setError('Unexpected error, please try again')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="login-page">
      <form className="login-form" onSubmit={handleSubmit}>
        <h1>{mode === 'login' ? 'Sign in' : 'Create account'}</h1>
        {error && <p className="error">{error}</p>}
        <label>
          Username
          <input
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            required
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            minLength={mode === 'register' ? 8 : undefined}
            required
          />
        </label>
        <button type="submit" disabled={submitting}>
          {submitting ? '…' : mode === 'login' ? 'Sign in' : 'Create account'}
        </button>
        <button type="button" className="switch" onClick={() => setMode(mode === 'login' ? 'register' : 'login')} disabled={submitting}>
          {mode === 'login' ? 'No account? Create one' : 'Have an account? Sign in'}
        </button>
      </form>
    </div>
  )
}
