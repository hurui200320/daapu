import { useCallback, useEffect, useRef, useState } from 'react'
import { api, isUnauthorized } from '../api/client'
import type { ChatInfo, ChatMessage, User } from '../api/types'

interface ChatPageProps {
  user: User
  onLogout: () => void
}

export function ChatPage({ user, onLogout }: ChatPageProps) {
  const [chats, setChats] = useState<ChatInfo[]>([])
  const [activeId, setActiveId] = useState<number | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [draft, setDraft] = useState('')
  const [streaming, setStreaming] = useState(false)
  const abortRef = useRef<(() => void) | null>(null)
  // Monotonic counter so each optimistic message gets a unique local id.
  const localIdRef = useRef(0)
  // Id of the in-flight optimistic assistant message, so Stop can drop its bubble.
  const assistantMessageIdRef = useRef(0)
  // Guards against out-of-order listMessages responses when switching chats.
  const chatLoadSeq = useRef(0)
  // Identifies the most recent send; callbacks from an earlier, aborted request
  // must not touch shared state (streaming flag, abortRef, logout).
  const sendSeqRef = useRef(0)
  // Set when the user intentionally aborts a stream, so the abort is not logged.
  const stoppedRef = useRef(false)
  const messagesEndRef = useRef<HTMLDivElement>(null)

  const loadChats = useCallback(async () => {
    try {
      setChats(await api.listChats())
    } catch (err) {
      if (isUnauthorized(err)) {
        onLogout()
      } else {
        console.error('Failed to load chats', err)
      }
    }
  }, [onLogout])

  useEffect(() => {
    void loadChats()
  }, [loadChats])

  const openChat = useCallback(
    async (id: number) => {
      const seq = ++chatLoadSeq.current
      setActiveId(id)
      setMessages([])
      try {
        const loaded = await api.listMessages(id)
        // Ignore stale responses from earlier, superseded requests.
        if (seq === chatLoadSeq.current) {
          setMessages(loaded)
        }
      } catch (err) {
        if (isUnauthorized(err)) {
          onLogout()
        } else {
          console.error('Failed to load messages', err)
        }
      }
    },
    [onLogout],
  )

  async function createChat() {
    try {
      const { id } = await api.createChat()
      await loadChats()
      await openChat(id)
    } catch (err) {
      if (isUnauthorized(err)) {
        onLogout()
      } else {
        console.error('Failed to create chat', err)
      }
    }
  }

  async function deleteChat(id: number) {
    try {
      await api.deleteChat(id)
      if (activeId === id) {
        // Drop any in-flight listMessages load for the deleted chat.
        chatLoadSeq.current++
        setActiveId(null)
        setMessages([])
      }
      await loadChats()
    } catch (err) {
      if (isUnauthorized(err)) {
        onLogout()
      } else {
        console.error('Failed to delete chat', err)
      }
    }
  }

  async function handleLogout() {
    try {
      await api.logout()
    } finally {
      onLogout()
    }
  }

  function stop() {
    stoppedRef.current = true
    abortRef.current?.()
    abortRef.current = null
    setMessages((prev) => prev.filter((m) => m.id !== assistantMessageIdRef.current))
    setStreaming(false)
  }

  function send() {
    const content = draft.trim()
    if (!content || activeId == null || streaming) return
    const seq = ++sendSeqRef.current
    const userMessageId = --localIdRef.current
    const assistantMessageId = --localIdRef.current
    assistantMessageIdRef.current = assistantMessageId
    stoppedRef.current = false
    setDraft('')
    setStreaming(true)
    setMessages((prev) => [
      ...prev,
      { id: userMessageId, role: 'USER', content, createdAt: '' },
      { id: assistantMessageId, role: 'ASSISTANT', content: '', createdAt: '' },
    ])

    const isCurrent = () => sendSeqRef.current === seq

    abortRef.current = api.sendMessage(
      activeId,
      content,
      (accumulated) => {
        if (!isCurrent()) return
        setMessages((prev) =>
          prev.map((m) => (m.id === assistantMessageId ? { ...m, content: accumulated } : m)),
        )
      },
      () => {
        if (!isCurrent()) return
        abortRef.current = null
        // Keep the composer disabled until the persisted messages are reloaded,
        // so a re-send can't race the pending openChat and lose live updates.
        void (async () => {
          await openChat(activeId)
          setStreaming(false)
        })()
      },
      (err) => {
        if (!isCurrent()) return
        abortRef.current = null
        // Drop the optimistic assistant bubble, mirroring stop().
        setMessages((prev) => prev.filter((m) => m.id !== assistantMessageIdRef.current))
        setStreaming(false)
        if (isUnauthorized(err)) {
          onLogout()
        } else if (!stoppedRef.current) {
          console.error('Failed to send message', err)
        }
      },
    )
  }

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'end' })
  }, [messages])

  return (
    <div className="chat-page">
      <aside className="sidebar">
        <div className="sidebar-header">
          <strong>{user.username}</strong>
          <button type="button" onClick={() => void handleLogout()} disabled={streaming}>
            Log out
          </button>
        </div>
        <button type="button" className="new-chat" onClick={() => void createChat()} disabled={streaming}>
          + New chat
        </button>
        <ul className="chat-list">
          {chats.map((chat) => (
            <li key={chat.id} className={chat.id === activeId ? 'active' : ''}>
              <button type="button" onClick={() => void openChat(chat.id)} disabled={streaming}>
                {chat.title}
              </button>
              <button
                type="button"
                className="delete"
                aria-label={`Delete ${chat.title}`}
                onClick={() => void deleteChat(chat.id)}
                disabled={streaming}
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      </aside>
      <main className="chat-main">
        {activeId == null ? (
          <div className="chat-empty">Select a chat or start a new chat.</div>
        ) : (
          <>
            <div className="messages">
              {messages.map((message) => (
                <div key={message.id} className={`message ${message.role.toLowerCase()}`}>
                  <div className="message-body">{message.content}</div>
                </div>
              ))}
              <div ref={messagesEndRef} />
            </div>
            <div className="composer">
              <textarea
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault()
                    send()
                  }
                }}
                placeholder="Type a message…"
                disabled={streaming}
              />
              {streaming ? (
                <button type="button" onClick={stop}>
                  Stop
                </button>
              ) : (
                <button type="button" onClick={() => send()} disabled={!draft.trim()}>
                  Send
                </button>
              )}
            </div>
          </>
        )}
      </main>
    </div>
  )
}
