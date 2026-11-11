// SPDX-License-Identifier: Apache-2.0
/**
 * API client of Auto-Ontology. Every URL is `baseUrl + apiPrefix + path` (FR-FE-05); the clients
 * only know the remaining path. Requests use the host's `fetch`/`headers` (NFR-QD-06a); no
 * credentials are involved (internal tool without login).
 */
export interface QuasarOntologyOptions {
  baseUrl?: string
  /** Must match `ao.api-prefix` of the backend. */
  apiPrefix?: string
  fetch?: typeof fetch
  headers?: Record<string, string> | (() => Record<string, string>)
  /** Value of the `x-auto-ontology-source` header (analytics), default `app`. */
  source?: string
}

export class AoApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly body: unknown,
  ) {
    super(message)
  }
}

export interface AoClient {
  url(path: string): string
  get<T>(path: string): Promise<T>
  post<T>(path: string, body?: unknown): Promise<T>
  put<T>(path: string, body?: unknown): Promise<T>
  patch<T>(path: string, body?: unknown): Promise<T>
  del<T>(path: string): Promise<T>
  upload<T>(path: string, form: FormData): Promise<T>
  stream(path: string, body: unknown, onEvent: (e: AoEvent) => void, signal?: AbortSignal): Promise<void>
  watch(path: string, onEvent: (e: AoEvent) => void, signal?: AbortSignal): Promise<void>
  download(path: string, body: unknown): Promise<Blob>
}

/** One SSE event of the chat stream (FR-AGT-07). */
export type AoEvent = Record<string, unknown> & { type: string }

function errorMessage(body: unknown, fallback: string): string {
  if (body && typeof body === 'object') {
    const b = body as Record<string, unknown>
    const d = b.detail ?? b.error
    if (typeof d === 'string') return d
    if (Array.isArray(d)) return d.map((x) => (x as { msg?: string }).msg ?? String(x)).join('; ')
  }
  if (typeof body === 'string' && body) return body
  return fallback
}

/** Robust SSE line parser: works across split chunks; `[DONE]` ends the stream. */
export function createSseParser(onEvent: (e: AoEvent) => void): { push(chunk: string): boolean; flush(): void } {
  let buffer = ''
  let done = false
  const handle = (line: string) => {
    if (!line.startsWith('data:')) return
    const payload = line.slice(5).trim()
    if (payload === '[DONE]') {
      done = true
      return
    }
    if (!payload) return
    try {
      onEvent(JSON.parse(payload) as AoEvent)
    } catch {
      // ignore malformed lines
    }
  }
  return {
    push(chunk: string) {
      buffer += chunk
      let nl = buffer.indexOf('\n')
      while (nl >= 0 && !done) {
        handle(buffer.slice(0, nl).replace(/\r$/, ''))
        buffer = buffer.slice(nl + 1)
        nl = buffer.indexOf('\n')
      }
      return done
    },
    flush() {
      if (buffer && !done) handle(buffer)
      buffer = ''
    },
  }
}

export function createAoClient(options: QuasarOntologyOptions = {}): AoClient {
  const base = (options.baseUrl ?? '').replace(/\/$/, '')
  const prefix = options.apiPrefix ?? '/api'
  const f = options.fetch ?? ((...args: Parameters<typeof fetch>) => fetch(...args))
  const headers = () => ({
    ...(typeof options.headers === 'function' ? options.headers() : (options.headers ?? {})),
    'x-auto-ontology-source': options.source ?? 'app',
  })
  const url = (path: string) => `${base}${prefix}${path}`

  async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const init: RequestInit = { method, headers: { ...headers(), Accept: 'application/json' } }
    if (body !== undefined) {
      init.body = JSON.stringify(body)
      init.headers = { ...init.headers, 'Content-Type': 'application/json' }
    }
    const resp = await f(url(path), init)
    const text = await resp.text()
    let data: unknown = text
    try {
      data = text ? JSON.parse(text) : null
    } catch {
      // plain text body
    }
    if (!resp.ok) throw new AoApiError(errorMessage(data, `${resp.status} ${resp.statusText}`), resp.status, data)
    return data as T
  }

  async function readStream(resp: Response, onEvent: (e: AoEvent) => void) {
    if (!resp.ok) {
      const text = await resp.text()
      let data: unknown = text
      try {
        data = JSON.parse(text)
      } catch {
        // plain text
      }
      throw new AoApiError(errorMessage(data, `${resp.status}`), resp.status, data)
    }
    const reader = resp.body?.getReader()
    if (!reader) return
    const decoder = new TextDecoder()
    const parser = createSseParser(onEvent)
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      if (parser.push(decoder.decode(value, { stream: true }))) {
        await reader.cancel()
        return
      }
    }
    parser.flush()
  }

  return {
    url,
    get: (p) => request('GET', p),
    post: (p, b) => request('POST', p, b ?? {}),
    put: (p, b) => request('PUT', p, b ?? {}),
    patch: (p, b) => request('PATCH', p, b ?? {}),
    del: (p) => request('DELETE', p),
    async upload(path, form) {
      const resp = await f(url(path), { method: 'POST', headers: headers(), body: form })
      const data = await resp.json().catch(() => null)
      if (!resp.ok) throw new AoApiError(errorMessage(data, String(resp.status)), resp.status, data)
      return data
    },
    // POST with body: fetch + ReadableStream instead of EventSource (FR-FE-03)
    async stream(path, body, onEvent, signal) {
      const init: RequestInit = {
        method: 'POST',
        headers: { ...headers(), 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify(body),
      }
      if (signal) init.signal = signal
      await readStream(await f(url(path), init), onEvent)
    },
    async watch(path, onEvent, signal) {
      const init: RequestInit = { headers: { ...headers(), Accept: 'text/event-stream' } }
      if (signal) init.signal = signal
      await readStream(await f(url(path), init), onEvent)
    },
    async download(path, body) {
      const resp = await f(url(path), {
        method: 'POST',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (!resp.ok) throw new AoApiError(String(resp.status), resp.status, await resp.text())
      return resp.blob()
    },
  }
}
