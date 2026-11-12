// SPDX-License-Identifier: Apache-2.0
import type { ChartParams, QueryResult } from '@jentsch/quasar-dashboard'

/** A stored chart block of the chat (`{viz_type, params, mode, dataset_id?}`, NFR-QD-08). */
export interface ChartBlock {
  viz_type: string
  params: Record<string, unknown>
  mode: 'semantic' | 'sql'
  dataset_id?: string | number
}

const ISO = /^\d{4}-\d{2}-\d{2}([T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?)?(Z|[+-]\d{2}:?\d{2})?$/

/**
 * Agent rows → QueryResult (FR-NLC-14): coltypes 0 numeric, 1 text, 2 temporal (epoch ms UTC),
 * 3 boolean; decimals rounded to 15 significant digits.
 */
export function rowsToQueryResult(rows: Array<Record<string, unknown>>, sql?: string): QueryResult {
  const colnames = rows.length ? Object.keys(rows[0] ?? {}) : []
  const coltypes = colnames.map((c) => {
    const sample = rows.map((r) => r[c]).find((v) => v !== null && v !== undefined)
    if (typeof sample === 'number') return 0
    if (typeof sample === 'boolean') return 3
    if (typeof sample === 'string' && ISO.test(sample)) return 2
    if (typeof sample === 'string' && sample.trim() !== '' && !Number.isNaN(Number(sample))) return 0
    return 1
  })
  const data = rows.map((r) => {
    const out: Record<string, string | number | boolean | null> = {}
    colnames.forEach((c, i) => {
      const v = r[c]
      if (v === null || v === undefined) out[c] = null
      else if (coltypes[i] === 2) out[c] = Date.parse(String(v).length === 10 ? `${v}T00:00:00Z` : String(v))
      else if (coltypes[i] === 0) out[c] = Number(Number(v).toPrecision(15))
      else if (coltypes[i] === 3) out[c] = Boolean(v)
      else out[c] = String(v)
    })
    return out
  })
  return {
    colnames,
    coltypes: coltypes as QueryResult['coltypes'],
    rowcount: data.length,
    data,
    applied_filters: [],
    rejected_filters: [],
    from_dttm: null,
    to_dttm: null,
    ...(sql ? { query: sql } : {}),
    is_cached: false,
    queried_dttm: new Date().toISOString(),
  }
}

export function chartParams(block: ChartBlock): ChartParams {
  return { viz_type: block.viz_type, ...block.params } as unknown as ChartParams
}

/** Parses ```chart blocks of a stored message (FR-CHAT-04). */
export function parseChartBlocks(content: string | null | undefined): ChartBlock[] {
  if (!content) return []
  const out: ChartBlock[] = []
  const re = /```chart\n([\s\S]*?)\n```/g
  let m: RegExpExecArray | null
  while ((m = re.exec(content))) {
    try {
      out.push(JSON.parse(m[1] ?? '') as ChartBlock)
    } catch {
      // skip
    }
  }
  return out
}

/** A UUID also where `crypto.randomUUID` is missing (plain http on a host other than localhost). */
export function newUuid(): string {
  const c = globalThis.crypto as Crypto | undefined
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  const bytes = new Uint8Array(16)
  if (c && typeof c.getRandomValues === 'function') c.getRandomValues(bytes)
  else for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256)
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** CSV download of result rows. */
export function toCsv(rows: Array<Record<string, unknown>>): string {
  if (!rows.length) return ''
  const cols = Object.keys(rows[0] ?? {})
  const esc = (v: unknown) => {
    const s = v === null || v === undefined ? '' : String(v)
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
  }
  return [cols.join(','), ...rows.map((r) => cols.map((c) => esc(r[c])).join(','))].join('\n')
}

/** `[[[name/id]]]` → markdown-ish link placeholders (FR-FE-04). */
export function entityLinks(text: string): Array<{ text: string; id?: string }> {
  const parts: Array<{ text: string; id?: string }> = []
  const re = /\[\[\[([^/\]]+)\/([^\]]+)\]\]\]/g
  let last = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(text))) {
    if (m.index > last) parts.push({ text: text.slice(last, m.index) })
    parts.push({ text: m[1] ?? '', id: m[2] ?? '' })
    last = m.index + m[0].length
  }
  if (last < text.length) parts.push({ text: text.slice(last) })
  return parts
}
