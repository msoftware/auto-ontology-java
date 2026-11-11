// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { parseSse } from '../src/plugin'
import { messages } from '../src/i18n'

describe('NL-chart client', () => {
  it('parses suggestion streams across chunks', () => {
    const events: unknown[] = []
    const push = parseSse((e) => events.push(e))
    push('data: {"type":"step","node":"choose_dataset","phase":"start"}\n\ndata: {"type":"chart_sugg')
    expect(push('estion","mode":"semantic"}\n\ndata: [DONE]\n')).toBe(true)
    expect(events).toHaveLength(2)
  })
  it('has the same message keys in both languages', () => {
    expect(Object.keys(messages.de.qdnl).sort()).toEqual(Object.keys(messages.en.qdnl).sort())
  })
})
