import type { ChatMessage, MemoryDto, ModelInfo, StreamEvent } from './types'

async function parseError(res: Response): Promise<string> {
  try {
    const body = await res.json()
    return body.error ?? res.statusText
  } catch {
    return res.statusText
  }
}

export async function listModels(): Promise<ModelInfo[]> {
  const res = await fetch('/api/models')
  if (!res.ok) throw new Error(await parseError(res))
  return res.json()
}

export async function listChats(): Promise<string[]> {
  const res = await fetch('/api/chats')
  if (!res.ok) throw new Error(await parseError(res))
  return res.json()
}

export async function newChat(): Promise<string> {
  const res = await fetch('/api/chats', { method: 'POST' })
  if (!res.ok) throw new Error(await parseError(res))
  return (await res.json()).id
}

export async function deleteChat(chatId: string): Promise<void> {
  const res = await fetch(`/api/chats/${encodeURIComponent(chatId)}`, { method: 'DELETE' })
  // a never-messaged chat has no row yet, so deleting it 404s; treat that as
  // success so the UI can drop the id from its list
  if (!res.ok && res.status !== 404) throw new Error(await parseError(res))
}

export async function loadChat(chatId: string): Promise<ChatMessage[]> {
  const res = await fetch(`/api/chats/${encodeURIComponent(chatId)}/chat`)
  if (!res.ok) throw new Error(await parseError(res))
  return res.json()
}

export interface SendMessageRequest {
  text?: string
  images?: { dataUrl: string }[]
  /** required by the server: no default model exists (the UI picks one per message) */
  model: string
}

/**
 * POST the message and stream the response as SSE events. EventSource can't
 * POST, so the stream is parsed manually from a fetch reader (same technique
 * llama.cpp's own webui uses).
 */
export async function* streamChat(chatId: string, body: SendMessageRequest): AsyncGenerator<StreamEvent> {
  const res = await fetch(`/api/chats/${encodeURIComponent(chatId)}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok || !res.body) throw new Error(await parseError(res))
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx: number
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        const parsed = parseBlock(block)
        if (parsed) yield parsed
      }
    }
  } finally {
    reader.releaseLock()
  }
}

function parseBlock(block: string): StreamEvent | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
  }
  if (dataLines.length === 0) return null
  return { event, data: dataLines.join('\n') }
}

export async function listMemories(): Promise<MemoryDto[]> {
  const res = await fetch('/api/memories')
  if (!res.ok) throw new Error(await parseError(res))
  return res.json()
}

export async function createMemory(content: string): Promise<MemoryDto> {
  const res = await fetch('/api/memories', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  })
  if (!res.ok) throw new Error(await parseError(res))
  return res.json()
}

export async function updateMemory(id: number, content: string): Promise<MemoryDto> {
  const res = await fetch(`/api/memories/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  })
  if (!res.ok) throw new Error(await parseError(res))
  return res.json()
}

export async function deleteMemory(id: number): Promise<void> {
  const res = await fetch(`/api/memories/${id}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(await parseError(res))
}
