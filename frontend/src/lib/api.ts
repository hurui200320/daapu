import type {
  ChatAttachmentPart,
  ChatExport,
  ChatInfo,
  ChatMessage,
  EntityViewDto,
  EltmNoteDto,
  ModelInfo,
  Persona,
  RelationshipViewDto,
  StreamEvent,
  TextPart,
} from './types'

async function parseError(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { error?: unknown }
    // the backend always sends {"error": "<message>"}; anything structured
    // would render "[object Object]" — fall back to the status text instead
    return typeof body?.error === 'string' && body.error.length > 0 ? body.error : res.statusText
  } catch {
    return res.statusText
  }
}

/** Fetch + error normalization: a non-2xx response throws with the backend's error message. */
async function request(path: string, init?: RequestInit): Promise<Response> {
  const res = await fetch(path, init)
  if (!res.ok) throw new Error(await parseError(res))
  return res
}

/** GET returning the parsed JSON body. */
function getJson<T>(path: string): Promise<T> {
  return request(path).then((res) => res.json())
}

/** RequestInit carrying a JSON request body (Content-Type + serialization). */
function jsonInit(method: string, body: unknown): RequestInit {
  return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
}

export async function listModels(): Promise<ModelInfo[]> {
  return getJson('/api/models')
}

export async function listChats(): Promise<ChatInfo[]> {
  return getJson('/api/chats')
}

export async function newChat(): Promise<string> {
  const res = await request('/api/chats', { method: 'POST' })
  return (await res.json()).id
}

export async function renameChat(chatId: string, title: string): Promise<void> {
  await request(`/api/chats/${encodeURIComponent(chatId)}`, jsonInit('PUT', { title }))
}

/** Generate a session title from the chat's history; returns the new title. */
export async function generateTitle(chatId: string): Promise<ChatInfo> {
  const res = await request(`/api/chats/${encodeURIComponent(chatId)}/title`, { method: 'POST' })
  return res.json()
}

export async function deleteChat(chatId: string): Promise<void> {
  await request(`/api/chats/${encodeURIComponent(chatId)}`, { method: 'DELETE' })
}

/**
 * Drop the message at `index` (a user message) and everything after it.
 * The dropped tail is NOT extracted into memories. 204 on success.
 */
export async function truncateMessages(chatId: string, index: number): Promise<void> {
  await request(`/api/chats/${encodeURIComponent(chatId)}/messages/${index}`, { method: 'DELETE' })
}

/**
 * Fork: copy the history up to and including the message at `index` (an
 * assistant message that ended naturally) into a new chat; returns its info.
 */
export async function forkChat(chatId: string, index: number): Promise<ChatInfo> {
  const res = await request(`/api/chats/${encodeURIComponent(chatId)}/fork/${index}`, { method: 'POST' })
  return res.json()
}

export async function loadChat(chatId: string): Promise<ChatMessage[]> {
  return getJson(`/api/chats/${encodeURIComponent(chatId)}/chat`)
}

/**
 * Export a chat: the title plus the full neutral-format history (no chat
 * id, see ChatExport). Returns the parsed payload — triggering the
 * download (and its file naming, see the backend's export route,
 * ChatsRoute.kt) is the caller's job.
 */
export async function exportChat(chatId: string): Promise<ChatExport> {
  return getJson(`/api/chats/${encodeURIComponent(chatId)}/export`)
}

/**
 * Import an exported payload: creates a NEW chat reusing the title (fresh
 * fork-like state) with the stored-chat validation applied server-side —
 * 400 with the reason on violation. Returns the created chat's info.
 */
export async function importChat(payload: ChatExport): Promise<ChatInfo> {
  const res = await request('/api/chats/import', jsonInit('POST', payload))
  return res.json()
}

interface SendMessageRequest {
  text?: string
  images?: { dataUrl: string }[]
  /** server-required (see `agent/chat/ChatService.kt` `prepareRun`) */
  model: string
  /** server-required (see `agent/chat/ChatService.kt` `prepareRun`) */
  personaId: number
}

// ---- personas ----

export interface PersonaSaveBody {
  name: string
  systemPrompt: string
  /** see `Persona.allowedNamespaces` (types.ts) */
  allowedNamespaces: string[]
}

/**
 * All personas: the code-only default persona first (id
 * [DEFAULT_PERSONA_ID], read-only), then the `personas` rows.
 */
export async function listPersonas(): Promise<Persona[]> {
  return getJson('/api/personas')
}

export async function createPersona(body: PersonaSaveBody): Promise<Persona> {
  const res = await request('/api/personas', jsonInit('POST', body))
  return res.json()
}

export async function updatePersona(id: number, body: PersonaSaveBody): Promise<Persona> {
  const res = await request(`/api/personas/${id}`, jsonInit('PUT', body))
  return res.json()
}

export async function deletePersona(id: number): Promise<void> {
  await request(`/api/personas/${id}`, { method: 'DELETE' })
}

/**
 * POST the message and stream the response as SSE events. EventSource can't
 * POST, so the stream is parsed manually from a fetch reader (same technique
 * llama.cpp's own webui uses).
 */
export async function* streamChat(chatId: string, body: SendMessageRequest): AsyncGenerator<StreamEvent> {
  const res = await request(`/api/chats/${encodeURIComponent(chatId)}/messages`, jsonInit('POST', body))
  if (!res.body) throw new Error('empty response body')
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      // normalize CRLF block/line delimiters to LF across the WHOLE pending
      // buffer (not just this chunk — a delimiter may straddle reads). The
      // SSE spec allows CRLF; our backend always emits LF, but anything
      // rewriting newlines in transit must still parse. Raw CR never appears
      // inside event payloads here (deltas are JSON-encoded), so a blanket
      // replacement cannot corrupt content.
      buffer += decoder.decode(value, { stream: true })
      if (buffer.includes('\r')) buffer = buffer.replace(/\r\n/g, '\n')
      let idx: number
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        const parsed = parseBlock(block)
        if (parsed) yield parsed
      }
    }
  } finally {
    // cancel aborts the response when the loop exits early (a parse error
    // or a future consumer break); a normally-completed stream is already
    // closed, so the cancel is a no-op there
    try {
      await reader.cancel()
    } catch {
      // the connection died on its own; nothing to abort
    }
    reader.releaseLock()
  }
}

/** Pure SSE block parser, exported for unit tests. */
export function parseBlock(block: string): StreamEvent | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    // the SSE spec strips exactly ONE leading space after "data:" — a
    // trimStart would eat significant whitespace in a future payload
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
  }
  if (dataLines.length === 0) return null
  return { event, data: dataLines.join('\n') }
}

// ---- ELTM browse (read-only; writes are LLM-driven) ----

/**
 * Page cap of the ELTM drill-down fetches (entity/relationship notes). The
 * view fetches one probe row past it: only a payload that RETURNS the extra
 * row is truncated, so the hint never claims older notes for a subject with
 * exactly this many. Exported so the api default and the view's hint cannot
 * drift apart.
 */
export const ELTM_DRILLDOWN_LIMIT = 100

export async function listEntities(limit = 100, offset = 0): Promise<EntityViewDto[]> {
  return getJson(`/api/eltm/entities?limit=${limit}&offset=${offset}`)
}

export async function getEntityRelationships(id: number, includeInvalid = true): Promise<RelationshipViewDto[]> {
  return getJson(`/api/eltm/entities/${id}/relationships?includeInvalid=${includeInvalid}`)
}

export async function getEntityNotes(id: number, limit = ELTM_DRILLDOWN_LIMIT): Promise<EltmNoteDto[]> {
  return getJson(`/api/eltm/entities/${id}/notes?limit=${limit}`)
}

export async function listRelationships(limit = 100, offset = 0): Promise<RelationshipViewDto[]> {
  return getJson(`/api/eltm/relationships?limit=${limit}&offset=${offset}`)
}

export async function getRelationshipNotes(id: number, limit = ELTM_DRILLDOWN_LIMIT): Promise<EltmNoteDto[]> {
  return getJson(`/api/eltm/relationships/${id}/notes?limit=${limit}`)
}

// ---- ELTM import (the manual write path; see the `#/eltm` Import tab) ----

/** An import part: the text/image subset of the chat message parts. */
export type EltmImportPart = TextPart | ChatAttachmentPart

/**
 * Feed caller-supplied material into the ELTM import pipeline (`POST
 * /api/eltm/import`): the memory extraction one-shot normalizes it into
 * the extractor's fact tone (first-person pronouns resolve to "the user",
 * relative dates resolve against `date`), then the ELTM writer agent
 * records the extracted facts (see EltmRoute.kt). `parts` are ordered
 * text/image parts (see EltmImportPart) — an email or a document imports
 * with its interleaving intact; text may be anything — raw notes, prose,
 * pre-digested facts — and images are read by the extraction model (the
 * server's extraction model must support vision; the Import tab carries
 * the full notice). At least one non-blank text part or image must be
 * present. `date` is the optional reference date (`YYYY-MM-DD`); omit it
 * for the server's today. The request blocks for both stages (minutes are
 * normal). Success — including a pasted skip sentinel or an empty
 * extraction, an indistinguishable no-op — answers 201 Created with an
 * empty body (there is no recorded flag).
 */
export async function importEltm(parts: EltmImportPart[], date?: string): Promise<void> {
  await request('/api/eltm/import', jsonInit('POST', { parts, date }))
}
