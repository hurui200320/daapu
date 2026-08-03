import { useEffect, useState } from 'react'
import { Navigate, Outlet, useOutletContext } from 'react-router'
import { api, isUnauthorized } from './api/client'
import type { User } from './api/types'
import { ChatPage } from './pages/ChatPage'
import { LoginPage } from './pages/LoginPage'

interface AuthContext {
  user: User | null
  setUser: (user: User | null) => void
}

export const SESSION_RESTORE_TIMEOUT_MS = 10_000

export function RootLayout() {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)

  // On load, restore the session from the cookie if one is still valid.
  useEffect(() => {
    let cancelled = false
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), SESSION_RESTORE_TIMEOUT_MS)
    void api
      .me(controller.signal)
      .then((me) => {
        if (!cancelled) setUser(me)
      })
      .catch((error: unknown) => {
        if (isUnauthorized(error)) {
          // No valid session; stay logged out.
        } else if (error instanceof DOMException && error.name === 'AbortError') {
          // Deliberate timeout; give up on the restore silently.
        } else if (!cancelled) {
          console.error('Failed to restore session', error)
        }
      })
      .finally(() => {
        clearTimeout(timer)
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
      clearTimeout(timer)
      controller.abort()
    }
  }, [])

  if (loading) {
    return <div className="loading">Loading…</div>
  }

  return <Outlet context={{ user, setUser }} />
}

export function ChatRoute() {
  const { user, setUser } = useOutletContext<AuthContext>()
  if (!user) {
    return <Navigate to="/login" replace />
  }
  return <ChatPage user={user} onLogout={() => setUser(null)} />
}

export function LoginRoute() {
  const { user, setUser } = useOutletContext<AuthContext>()
  if (user) {
    return <Navigate to="/" replace />
  }
  return <LoginPage onLogin={setUser} />
}

export function CatchAll() {
  const { user } = useOutletContext<AuthContext>()
  return <Navigate to={user ? '/' : '/login'} replace />
}
