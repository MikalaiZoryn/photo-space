/** RFC 9457 problem body returned by the backend for every error. */
export type Problem = {
  status?: number
  title?: string
  detail?: string
  correlationId?: string | null
  /** Field name to message, for validation failures. */
  errors?: Record<string, string>
}

export class ApiError extends Error {
  readonly status: number
  readonly problem: Problem

  constructor(status: number, problem: Problem) {
    super(problem.title ?? `HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  signal?: AbortSignal
}

const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'

/**
 * Calls the backend same-origin with the session cookie. State-changing requests send the CSRF token from
 * the XSRF-TOKEN cookie back in a header, fetching the cookie first if the backend has not set it yet.
 */
export async function api<T>(path: string, { method = 'GET', body, signal }: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') headers[CSRF_HEADER] = await csrfToken(signal)

  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  })
  if (!response.ok) throw new ApiError(response.status, await readProblem(response))
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

async function csrfToken(signal?: AbortSignal): Promise<string> {
  let token = readCookie(CSRF_COOKIE)
  if (!token) {
    await fetch('/api/auth/csrf', { signal })
    token = readCookie(CSRF_COOKIE)
  }
  return token ?? ''
}

function readCookie(name: string): string | undefined {
  for (const part of document.cookie.split(';')) {
    const [key, ...value] = part.trim().split('=')
    if (key === name) return decodeURIComponent(value.join('='))
  }
  return undefined
}

async function readProblem(response: Response): Promise<Problem> {
  try {
    return (await response.json()) as Problem
  } catch {
    return { status: response.status }
  }
}
