import { vi } from 'vitest'

type Handler = (init: RequestInit | undefined, url: URL) => Response | Promise<Response>

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

export function noContent(): Response {
  return new Response(null, { status: 204 })
}

/**
 * Stubs fetch with handlers keyed by "METHOD /path". A key with a query string matches only that exact URL;
 * a key without one matches any query, and the handler can read it from `url.searchParams`. Unknown routes
 * return 404. Handlers build a fresh Response per call, since a body can be read only once.
 */
export function mockApi(routes: Record<string, Handler>) {
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const url = new URL(raw, 'http://localhost')
    const method = init?.method ?? 'GET'
    const handler = routes[`${method} ${url.pathname}${url.search}`] ?? routes[`${method} ${url.pathname}`]
    return handler ? handler(init, url) : json({ status: 404, title: 'Not Found' }, 404)
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

/** The headers a mocked fetch call was made with. */
export function headersOf(fn: ReturnType<typeof mockApi>, method: string, path: string): Record<string, string> {
  const call = fn.mock.calls.find(([input, init]) => input === path && (init?.method ?? 'GET') === method)
  if (!call) throw new Error(`No ${method} ${path} call`)
  return (call[1]?.headers ?? {}) as Record<string, string>
}

/** The parsed JSON bodies of the mocked calls to one endpoint, oldest first. */
export function bodiesOf(fn: ReturnType<typeof mockApi>, method: string, path: string): unknown[] {
  return fn.mock.calls
    .filter(([input, init]) => input === path && (init?.method ?? 'GET') === method)
    .map(([, init]) => JSON.parse(String(init?.body)))
}

/** The query strings of the mocked GET calls to one path, e.g. "page=1&size=25". */
export function queriesOf(fn: ReturnType<typeof mockApi>, path: string): string[] {
  return fn.mock.calls
    .map(([input, init]) => ({ url: new URL(String(input), 'http://localhost'), method: init?.method ?? 'GET' }))
    .filter(({ url, method }) => url.pathname === path && method === 'GET')
    .map(({ url }) => url.search.replace(/^\?/, ''))
}

export function setCookie(value: string) {
  document.cookie = value
}

export function clearCookie(name: string) {
  document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT`
}
