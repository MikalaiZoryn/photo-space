import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCookie, headersOf, json, mockApi, noContent, setCookie } from '../test/mockApi.ts'
import { api, ApiError } from './client.ts'

describe('api client', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    clearCookie('XSRF-TOKEN')
  })

  it('sends the CSRF cookie value in the X-XSRF-TOKEN header on state changes', async () => {
    setCookie('XSRF-TOKEN=token-from-cookie')
    const fetch = mockApi({ 'POST /api/things': () => json({ ok: true }, 201) })

    await api('/api/things', { method: 'POST', body: { a: 1 } })

    expect(headersOf(fetch, 'POST', '/api/things')['X-XSRF-TOKEN']).toBe('token-from-cookie')
  })

  it('fetches the CSRF cookie first when it is missing', async () => {
    const fetch = mockApi({
      'GET /api/auth/csrf': () => {
        setCookie('XSRF-TOKEN=fresh-token')
        return noContent()
      },
      'POST /api/things': () => noContent(),
    })

    await api('/api/things', { method: 'POST' })

    expect(headersOf(fetch, 'POST', '/api/things')['X-XSRF-TOKEN']).toBe('fresh-token')
  })

  it('does not send a CSRF header on reads', async () => {
    const fetch = mockApi({ 'GET /api/things': () => json([]) })

    await api('/api/things')

    expect(headersOf(fetch, 'GET', '/api/things')['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('throws ApiError carrying the problem body', async () => {
    mockApi({
      'GET /api/things': () =>
        json({ status: 404, title: 'Not Found', correlationId: 'abc-123' }, 404),
    })

    const error = await api('/api/things').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(404)
    expect((error as ApiError).problem.correlationId).toBe('abc-123')
  })
})
