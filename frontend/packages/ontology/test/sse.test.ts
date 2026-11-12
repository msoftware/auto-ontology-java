// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { createSseParser } from '../src/api'
import { entityLinks, newUuid, parseChartBlocks, rowsToQueryResult, toCsv } from '../src/results'

describe('SSE parser (FR-FE-03)', () => {
  it('handles events split across chunks and stops at [DONE]', () => {
    const events: unknown[] = []
    const p = createSseParser((e) => events.push(e))
    expect(p.push('data: {"type":"st')).toBe(false)
    expect(p.push('ep","node":"a"}\n\n: ping\n\ndata:{"type":"result"}\n')).toBe(false)
    expect(p.push('\ndata: [DONE]\n\ndata: {"type":"late"}\n')).toBe(true)
    expect(events).toEqual([{ type: 'step', node: 'a' }, { type: 'result' }])
  })
})

describe('results', () => {
  it('converts rows to a QueryResult with coltypes', () => {
    const r = rowsToQueryResult([{ d: '2025-06-01', n: 1.23456789012345678, s: 'x', b: true }])
    expect(r.coltypes).toEqual([2, 0, 1, 3])
    expect(r.data[0]?.d).toBe(Date.parse('2025-06-01T00:00:00Z'))
    expect(r.rowcount).toBe(1)
  })
  it('parses chart blocks, csv and entity links', () => {
    expect(parseChartBlocks('```chart\n{"viz_type":"bar","params":{},"mode":"sql"}\n```')[0]?.viz_type).toBe('bar')
    expect(toCsv([{ a: 'x,y', b: 1 }])).toBe('a,b\n"x,y",1')
    expect(entityLinks('Total [[[Revenue/abc]]] now')).toEqual([{ text: 'Total ' }, { text: 'Revenue', id: 'abc' }, { text: ' now' }])
  })
  it('creates conversation ids the backend accepts, also without crypto.randomUUID', () => {
    const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
    expect(newUuid()).toMatch(uuid)
    const original = globalThis.crypto.randomUUID
    // plain http on a host other than localhost: the browser offers no randomUUID
    Object.defineProperty(globalThis.crypto, 'randomUUID', { value: undefined, configurable: true })
    try {
      const a = newUuid()
      expect(a).toMatch(uuid)
      expect(newUuid()).not.toBe(a)
    } finally {
      Object.defineProperty(globalThis.crypto, 'randomUUID', { value: original, configurable: true })
    }
  })
})
