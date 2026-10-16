import { vi } from 'vitest'

type Handler = (init: RequestInit | undefined) => Response | Promise<Response>

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

export function noContent(): Response {
  return new Response(null, { status: 204 })
}

/**
 * Stubs fetch with handlers keyed by "METHOD /path". Unknown routes return 404. Handlers build a fresh
 * Response per call, since a body can be read only once.
 */
export function mockApi(routes: Record<string, Handler>) {
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.pathname : input.url
    const handler = routes[`${init?.method ?? 'GET'} ${url}`]
    return handler ? handler(init) : json({ status: 404, title: 'Not Found' }, 404)
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

export function setCookie(value: string) {
  document.cookie = value
}

export function clearCookie(name: string) {
  document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT`
}
