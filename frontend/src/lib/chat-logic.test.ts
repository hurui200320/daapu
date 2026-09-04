import { describe, expect, it } from 'vitest'
import {
  applyToolResult,
  commitRoundParts,
  computeUsage,
  effectivePersonaId,
  mergeChatPage,
  runFailureText,
  type LiveRound,
} from './chat-logic'
import {
  DEFAULT_PERSONA_ID,
  type ChatInfo,
  type ChatListPage,
  type ChatMessage,
  type ChatMessagePart,
  type ChatToolResultPart,
  type ModelInfo,
  type Persona,
} from './types'

const persona = (id: number): Persona => ({ id, name: `p${id}`, systemPrompt: '', allowedNamespaces: [] })
const model = (id: string, contextLength: number): ModelInfo => ({
  id,
  vision: false,
  contextLength,
  // always present per the backend contract (validated > 0 at catalog boot)
  maxOutputTokens: 4096,
})
const call = (tool: string, args: Record<string, unknown> = {}): ChatMessagePart => ({
  type: 'tool_call',
  id: '',
  tool,
  args,
})
const resultPart = (id: string): ChatToolResultPart => ({
  type: 'tool_result',
  id,
  tool: 't',
  parts: [{ type: 'text', text: 'ok' }],
})
const assistant = (...parts: ChatMessagePart[]): ChatMessage => ({ role: 'assistant', parts })
const live = (over: Partial<LiveRound> = {}): LiveRound => ({ reasoning: '', text: '', toolCalls: [], ...over })

describe('effectivePersonaId', () => {
  const catalog = [persona(0), persona(5)]

  it('prefers the override when it exists in the catalog', () => {
    expect(effectivePersonaId(catalog, 5, 0)).toBe(5)
  })

  it('falls back to the recorded persona when the override was deleted', () => {
    expect(effectivePersonaId(catalog, 7, 5)).toBe(5)
  })

  it('falls back to the code default when neither survives', () => {
    expect(effectivePersonaId(catalog, 7, 9)).toBe(DEFAULT_PERSONA_ID)
  })

  it('trusts the override against an unloaded catalog (still loading)', () => {
    expect(effectivePersonaId([], 7, undefined)).toBe(7)
  })

  it('trusts the recorded persona against an unloaded catalog', () => {
    expect(effectivePersonaId([], null, 9)).toBe(9)
  })

  it('defaults with no override and no recorded persona', () => {
    expect(effectivePersonaId(catalog, null, undefined)).toBe(DEFAULT_PERSONA_ID)
  })
})

describe('computeUsage', () => {
  const assistantWithTokens = (total: number): ChatMessage => ({
    role: 'assistant',
    parts: [],
    meta: { totalTokens: total },
  })

  it('returns nulls for an empty history', () => {
    expect(computeUsage([], [model('m1', 1000)], 'm1')).toEqual({ used: null, context: null })
  })

  it('scans backwards past user messages and assistants without usage', () => {
    const messages: ChatMessage[] = [assistantWithTokens(50), { role: 'user', parts: [] }, assistant()]
    expect(computeUsage(messages, [model('m1', 1000)], 'm1').used).toBe(50)
  })

  it('prefers the newest assistant usage and pairs it with the selected model context', () => {
    const messages: ChatMessage[] = [assistantWithTokens(50), { role: 'user', parts: [] }, assistantWithTokens(80)]
    expect(computeUsage(messages, [model('m1', 1000)], 'm1')).toEqual({ used: 80, context: 1000 })
  })

  it('hides the context for an unknown selected model', () => {
    expect(computeUsage([assistantWithTokens(80)], [model('m1', 1000)], 'gone')).toEqual({ used: 80, context: null })
  })

  // no "null contextLength" case exists: the backend guarantees the field
  // (validated > 0 at catalog boot), so the only null-context branch is the
  // unknown-model one pinned above
})

describe('commitRoundParts', () => {
  it('is null for empty buffers', () => {
    expect(commitRoundParts(live())).toBeNull()
  })

  it('orders reasoning, text, then tool calls with blank ids', () => {
    expect(commitRoundParts(live({ reasoning: 'r', text: 't', toolCalls: [{ name: 'x', args: { a: 1 } }] }))).toEqual([
      { type: 'reasoning', content: 'r' },
      { type: 'text', text: 't' },
      { type: 'tool_call', id: '', tool: 'x', args: { a: 1 } },
    ])
  })
})

describe('applyToolResult', () => {
  it('commits the round (assistant message + result) on the first result of a batch', () => {
    const { messages, committedRound } = applyToolResult(
      [],
      live({ text: 'working', toolCalls: [{ name: 'x', args: {} }] }),
      resultPart('r1'),
    )
    expect(committedRound).toBe(true)
    expect(messages).toEqual([
      assistant({ type: 'text', text: 'working' }, call('x')),
      { role: 'tool_result', parts: [resultPart('r1')] },
    ])
  })

  it('appends only the result when the buffers hold nothing to commit', () => {
    const { messages, committedRound } = applyToolResult([], live(), resultPart('r1'))
    expect(committedRound).toBe(true)
    expect(messages).toEqual([{ role: 'tool_result', parts: [resultPart('r1')] }])
  })

  it('extends the committed tool_result message on 2nd..Nth results of a batch', () => {
    const committed: ChatMessage[] = [assistant(call('x')), { role: 'tool_result', parts: [resultPart('r1')] }]
    const { messages, committedRound } = applyToolResult(committed, live(), resultPart('r2'))
    expect(committedRound).toBe(false)
    expect(messages).toHaveLength(2)
    expect(messages[1].parts).toEqual([resultPart('r1'), resultPart('r2')])
  })

  it('extends the tool_result even with uncommitted text still buffered', () => {
    // unreachable from a well-formed backend (a round's results only arrive
    // after its own calls committed + wiped the buffers) — pinned so a
    // refactor cannot silently flip the branch order
    const committed: ChatMessage[] = [assistant(call('x')), { role: 'tool_result', parts: [resultPart('r1')] }]
    const { messages, committedRound } = applyToolResult(committed, live({ text: 'stray' }), resultPart('r2'))
    expect(committedRound).toBe(false)
    expect(messages[1].parts).toEqual([resultPart('r1'), resultPart('r2')])
  })

  it('does not mutate the input array', () => {
    const committed: ChatMessage[] = [assistant(call('x')), { role: 'tool_result', parts: [resultPart('r1')] }]
    applyToolResult(committed, live(), resultPart('r2'))
    expect(committed).toHaveLength(2)
    expect(committed[1].parts).toHaveLength(1)
  })
})

describe('runFailureText', () => {
  it('prefixes the shared wording used by both banner and toast', () => {
    expect(runFailureText('boom')).toBe('run failed: boom')
  })
})

describe('mergeChatPage', () => {
  const chat = (id: string, title = `t-${id}`): ChatInfo => ({ id, title, personaId: 0 })

  it('replaces wholesale when the page is the complete list (no nextCursor)', () => {
    // the common case (≤200 chats): the whole list arrives in one page
    expect(mergeChatPage([chat('old')], { chats: [chat('b'), chat('a')] })).toEqual([chat('b'), chat('a')])
  })

  it('folds a full page in: fresh entries win, server-side deletions drop, the older tail is kept', () => {
    // known list newest-first; the fetched page spans down to 'b', so:
    // 'd' (not in the page, newer than the boundary) was deleted server-side,
    // 'e'/'b' carry fresh titles in the page, 'a' sits below the boundary
    const known = [chat('e', 'stale-e'), chat('d'), chat('b', 'stale-b'), chat('a')]
    const page: ChatListPage = { chats: [chat('f'), chat('e'), chat('b')], nextCursor: 'b' }
    expect(mergeChatPage(known, page)).toEqual([chat('f'), chat('e'), chat('b'), chat('a')])
  })

  it('keeps only the page when nothing older is known', () => {
    const page: ChatListPage = { chats: [chat('b'), chat('a')], nextCursor: 'a' }
    expect(mergeChatPage([], page)).toEqual([chat('b'), chat('a')])
  })

  it('returns an empty page as-is (the list is empty now)', () => {
    // also covers a pathological empty page WITH a nextCursor: an empty
    // page is authoritative about the whole list either way
    expect(mergeChatPage([chat('old')], { chats: [], nextCursor: 'would-loop-forever' })).toEqual([])
  })
})
