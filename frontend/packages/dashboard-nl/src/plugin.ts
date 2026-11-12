// SPDX-License-Identifier: Apache-2.0
import { inject, type App, type InjectionKey, type Plugin } from 'vue'
import { I18nInjectionKey, type I18n } from 'vue-i18n'
import { messages } from './i18n'

/** Same request options as createQuasarDashboard (NFR-QD-06a); no credentials (internal tool). */
export interface QuasarDashboardNlOptions {
  baseUrl?: string
  /** Must match `ao.nl-chart.api-prefix`. */
  apiPrefix?: string
  /** Prefix of the dashboard API used for saving (`quasar-dashboard.api-prefix`). */
  dashboardApiPrefix?: string
  fetch?: typeof fetch
  headers?: Record<string, string> | (() => Record<string, string>)
}

export interface NlEvent {
  type: string
  [key: string]: unknown
}

export interface NlClient {
  suggest(body: Record<string, unknown>, onEvent: (e: NlEvent) => void, signal?: AbortSignal): Promise<void>
  datasets(): Promise<Array<Record<string, unknown>>>
  saveChart(body: Record<string, unknown>): Promise<Record<string, unknown>>
}

export const QDNL_CLIENT: InjectionKey<NlClient> = Symbol('qdnl-client')

export function useNlClient(): NlClient {
  const c = inject(QDNL_CLIENT, null)
  if (!c) throw new Error('[@jentsch/quasar-dashboard-nl] install createQuasarDashboardNl() first')
  return c
}

/** SSE lines → events; split chunks are joined; `[DONE]` ends (same parser as the chat). */
export function parseSse(onEvent: (e: NlEvent) => void) {
  let buffer = ''
  let done = false
  return (chunk: string): boolean => {
    buffer += chunk
    let nl = buffer.indexOf('\n')
    while (nl >= 0 && !done) {
      const line = buffer.slice(0, nl).replace(/\r$/, '')
      buffer = buffer.slice(nl + 1)
      if (line.startsWith('data:')) {
        const payload = line.slice(5).trim()
        if (payload === '[DONE]') done = true
        else if (payload) {
          try {
            onEvent(JSON.parse(payload) as NlEvent)
          } catch {
            // ignore malformed lines
          }
        }
      }
      nl = buffer.indexOf('\n')
    }
    return done
  }
}

export function createNlClient(o: QuasarDashboardNlOptions = {}): NlClient {
  const base = (o.baseUrl ?? '').replace(/\/$/, '')
  const prefix = o.apiPrefix ?? '/api/nl-chart'
  const dashboard = o.dashboardApiPrefix ?? '/api/v1'
  const f = o.fetch ?? ((...a: Parameters<typeof fetch>) => fetch(...a))
  const headers = () => (typeof o.headers === 'function' ? o.headers() : (o.headers ?? {}))
  async function json<T>(resp: Response): Promise<T> {
    const data = await resp.json().catch(() => null)
    if (!resp.ok) {
      const d = data as { detail?: unknown; message?: unknown } | null
      throw new Error(String(d?.detail ?? d?.message ?? resp.status))
    }
    return data as T
  }
  return {
    async suggest(body, onEvent, signal) {
      const init: RequestInit = {
        method: 'POST',
        headers: { ...headers(), 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify(body),
      }
      if (signal) init.signal = signal
      const resp = await f(`${base}${prefix}/suggest`, init)
      if (!resp.ok) await json(resp)
      if (!resp.body) throw new Error('The server sent no event stream')
      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      const push = parseSse(onEvent)
      for (;;) {
        const { value, done } = await reader.read()
        if (done) break
        if (push(decoder.decode(value, { stream: true }))) {
          // [DONE]: release the connection instead of leaving the rest of the body unread
          await reader.cancel()
          break
        }
      }
    },
    async datasets() {
      return (await json<{ data: Array<Record<string, unknown>> }>(await f(`${base}${prefix}/datasets`, { headers: headers() }))).data
    },
    // saving only through the dashboard API (section 23.6)
    async saveChart(body) {
      return json(
        await f(`${base}${dashboard}/chart/`, {
          method: 'POST',
          headers: { ...headers(), 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        }),
      )
    },
  }
}

/** Vue plugin; merges the `qdnl` messages into the host's vue-i18n (FR-NLC-19). */
export function createQuasarDashboardNl(options: QuasarDashboardNlOptions = {}): Plugin {
  return {
    install(app: App) {
      app.provide(QDNL_CLIENT, createNlClient(options))
      const symbol = (app as App & { __VUE_I18N_SYMBOL__?: symbol }).__VUE_I18N_SYMBOL__
      const i18n = app.runWithContext(
        () => (symbol ? inject<I18n | null>(symbol, null) : null) ?? inject<I18n | null>(I18nInjectionKey, null),
      )
      if (!i18n) throw new Error('[@jentsch/quasar-dashboard-nl] vue-i18n (legacy: false) must be installed first')
      i18n.global.mergeLocaleMessage('en', messages.en)
      i18n.global.mergeLocaleMessage('de', messages.de)
    },
  }
}
