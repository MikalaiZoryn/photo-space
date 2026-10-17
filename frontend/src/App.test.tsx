import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.tsx'
import { clearCookie, headersOf, json, mockApi, noContent, setCookie } from './test/mockApi.ts'

const me = { id: 'user-1', displayName: 'alice@example.com', email: 'alice@example.com' }
const health = () => json({ status: 'UP', database: 'UP' })
const unauthorized = () => json({ status: 401, title: 'Unauthorized' }, 401)
const options = (googleSignInUrl: string | null, devLogin: boolean) => () => json({ googleSignInUrl, devLogin })

function workspace(id: string, name: string) {
  return { id, name, timezone: 'Europe/Warsaw', role: 'OWNER', createdAt: '2026-10-15T10:00:00Z' }
}

const emptyPage = () => json({ items: [], page: 0, size: 25, totalItems: 0 })
const sessionsOnlyLayout = () =>
  json({ widgets: [{ type: 'UPCOMING_SESSIONS', width: 'FULL' }], customized: true, version: 1, updatedAt: null })

function members() {
  return json([{ userId: 'user-1', displayName: 'alice@example.com', role: 'OWNER', joinedAt: '2026-10-15T10:00:00Z' }])
}

describe('App', () => {
  beforeEach(() => {
    setCookie('XSRF-TOKEN=csrf-token')
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    clearCookie('XSRF-TOKEN')
    window.localStorage.clear()
    window.history.replaceState(null, '', '/')
  })

  it('offers Google sign-in when signed out', async () => {
    mockApi({
      'GET /api/me': unauthorized,
      'GET /api/auth/options': options('/api/auth/oauth2/authorization/google', false),
      'GET /api/health': health,
    })

    render(<App />)

    const link = await screen.findByRole('link', { name: 'Sign in with Google' })
    expect(link.getAttribute('href')).toBe('/api/auth/oauth2/authorization/google')
    expect(screen.queryByRole('form', { name: 'Development sign-in' })).toBeNull()
  })

  it('explains when no sign-in method is configured', async () => {
    mockApi({ 'GET /api/me': unauthorized, 'GET /api/auth/options': options(null, false), 'GET /api/health': health })

    render(<App />)

    expect(await screen.findByText(/No sign-in method is configured/)).toBeTruthy()
  })

  it('shows a sign-in error after a failed Google callback', async () => {
    window.history.replaceState(null, '', '/?signInError')
    mockApi({
      'GET /api/me': unauthorized,
      'GET /api/auth/options': options('/api/auth/oauth2/authorization/google', false),
      'GET /api/health': health,
    })

    render(<App />)

    expect(await screen.findByText('Sign-in did not complete. Please try again.')).toBeTruthy()
    expect(window.location.search).toBe('')
  })

  it('signs in with the dev form and onboards the first workspace', async () => {
    let signedIn = false
    let workspaces: unknown[] = []
    const fetch = mockApi({
      'GET /api/me': () => (signedIn ? json(me) : unauthorized()),
      'GET /api/auth/options': options(null, true),
      'GET /api/health': health,
      'POST /api/dev/login': () => {
        signedIn = true
        return noContent()
      },
      'GET /api/workspaces': () => json(workspaces),
      'POST /api/workspaces': (init) => {
        const body = JSON.parse(String(init?.body)) as { name: string }
        const created = workspace('ws-1', body.name)
        workspaces = [created]
        return json(created, 201)
      },
      'GET /api/workspaces/ws-1/members': members,
      'GET /api/workspaces/ws-1/dashboard/layout': sessionsOnlyLayout,
      'GET /api/workspaces/ws-1/sessions': emptyPage,
    })

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Sign in (dev)' }))

    expect(await screen.findByRole('heading', { name: 'Create your workspace' })).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Business name'), { target: { value: 'Alice Studio' } })
    fireEvent.change(screen.getByLabelText('Time zone'), { target: { value: 'Europe/Warsaw' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create workspace' }))

    expect(await screen.findByRole('heading', { name: 'Alice Studio' })).toBeTruthy()
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeTruthy()
    expect(await screen.findByText('No upcoming sessions.')).toBeTruthy()
    fireEvent.click(screen.getByRole('link', { name: 'Settings' }))
    fireEvent.click(await screen.findByRole('link', { name: 'Members' }))
    const memberList = await screen.findByRole('list', { name: 'Members' })
    expect(within(memberList).getByText('alice@example.com')).toBeTruthy()
    expect(within(memberList).getByText('Owner')).toBeTruthy()
    expect(headersOf(fetch, 'POST', '/api/workspaces')['X-XSRF-TOKEN']).toBe('csrf-token')
    expect(JSON.parse(String(fetch.mock.calls.find(([p, i]) => p === '/api/workspaces' && i?.method === 'POST')?.[1]?.body)))
      .toEqual({ name: 'Alice Studio', timezone: 'Europe/Warsaw' })
  })

  it('shows validation errors from the server', async () => {
    mockApi({
      'GET /api/me': () => json(me),
      'GET /api/auth/options': options(null, false),
      'GET /api/health': health,
      'GET /api/workspaces': () => json([]),
      'POST /api/workspaces': () =>
        json({ status: 400, title: 'Bad Request', errors: { timezone: 'must be an IANA time zone' } }, 400),
    })

    render(<App />)
    fireEvent.change(await screen.findByLabelText('Business name'), { target: { value: 'Studio' } })
    fireEvent.change(screen.getByLabelText('Time zone'), { target: { value: 'Mars/Base' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create workspace' }))

    expect(await screen.findByText('Time zone must be an IANA time zone')).toBeTruthy()
  })

  it('opens the last selected workspace and switches between workspaces', async () => {
    window.localStorage.setItem('photospace.selectedWorkspace', 'ws-2')
    window.location.hash = '#/clients/c-from-ws-2'
    const notFound = () => json({ status: 404, title: 'Not Found' }, 404)
    mockApi({
      'GET /api/me': () => json(me),
      'GET /api/auth/options': options(null, false),
      'GET /api/health': health,
      'GET /api/workspaces': () => json([workspace('ws-1', 'Alpha'), workspace('ws-2', 'Beta')]),
      'GET /api/workspaces/ws-1/dashboard/layout': sessionsOnlyLayout,
      'GET /api/workspaces/ws-1/sessions': emptyPage,
      'GET /api/workspaces/ws-2/sessions': emptyPage,
      'GET /api/workspaces/ws-2/clients/c-from-ws-2': notFound,
    })

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Beta' })).toBeTruthy()
    expect(await screen.findByText(/This client was not found/)).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Workspace'), { target: { value: 'ws-1' } })
    expect(await screen.findByRole('heading', { name: 'Alpha' })).toBeTruthy()
    // Record IDs in the URL belonged to the other workspace, so switching opens the dashboard.
    expect(await screen.findByText('No upcoming sessions.')).toBeTruthy()
    expect(window.location.hash).toBe('#/dashboard')
    expect(window.localStorage.getItem('photospace.selectedWorkspace')).toBe('ws-1')
  })

  it('falls back to the first workspace when the stored one is no longer available', async () => {
    window.localStorage.setItem('photospace.selectedWorkspace', 'gone')
    mockApi({
      'GET /api/me': () => json(me),
      'GET /api/auth/options': options(null, false),
      'GET /api/health': health,
      'GET /api/workspaces': () => json([workspace('ws-1', 'Alpha')]),
      'GET /api/workspaces/ws-1/sessions': emptyPage,
    })

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Alpha' })).toBeTruthy()
  })

  it('signs out and returns to the sign-in page', async () => {
    let signedIn = true
    const fetch = mockApi({
      'GET /api/me': () => (signedIn ? json(me) : unauthorized()),
      'GET /api/auth/options': options('/api/auth/oauth2/authorization/google', false),
      'GET /api/health': health,
      'GET /api/workspaces': () => json([]),
      'POST /api/auth/logout': () => {
        signedIn = false
        return noContent()
      },
    })

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('link', { name: 'Sign in with Google' })).toBeTruthy()
    expect(headersOf(fetch, 'POST', '/api/auth/logout')['X-XSRF-TOKEN']).toBe('csrf-token')
  })

  it('shows a retryable error when the backend is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))))

    render(<App />)

    expect(await screen.findByText(/Could not reach the server/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy()
  })
})
