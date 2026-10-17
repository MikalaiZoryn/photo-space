import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App.tsx'
import { bodiesOf, clearCookie, headersOf, json, mockApi, noContent, setCookie } from '../test/mockApi.ts'
import { renderWorkspace, workspace, WS } from '../test/workspace.tsx'
import { Workspaces } from '../Workspaces.tsx'

const TOKEN = 'A'.repeat(43)
const unauthorized = () => json({ status: 401, title: 'Unauthorized' }, 401)
const health = () => json({ status: 'UP', database: 'UP' })

function me(email: string) {
  return () => json({ id: 'user-2', displayName: email, email })
}

function preview(overrides: Record<string, unknown> = {}) {
  return {
    workspaceName: 'Alice Studio',
    invitedBy: 'alice@example.com',
    email: 'carol@example.com',
    state: 'PENDING',
    expiresAt: '2026-10-24T10:00:00Z',
    emailMatches: true,
    alreadyMember: false,
    ...overrides,
  }
}

function invitation(overrides: Record<string, unknown> = {}) {
  return {
    id: 'inv-1',
    email: 'carol@example.com',
    role: 'MEMBER',
    state: 'PENDING',
    emailStatus: 'SENT',
    expiresAt: '2026-10-24T10:00:00Z',
    emailSentAt: '2026-10-17T10:00:00Z',
    createdAt: '2026-10-17T10:00:00Z',
    ...overrides,
  }
}

const members = () =>
  json([{ userId: 'user-1', displayName: 'alice@example.com', role: 'OWNER', joinedAt: '2026-10-15T10:00:00Z' }])

describe('Invitations', () => {
  beforeEach(() => {
    setCookie('XSRF-TOKEN=csrf-token')
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    clearCookie('XSRF-TOKEN')
    window.localStorage.clear()
    window.sessionStorage.clear()
    window.history.replaceState(null, '', '/')
  })

  it('accepts an invitation from its link and opens the joined workspace', async () => {
    window.history.replaceState(null, '', `/#/invite/${TOKEN}`)
    let joined = false
    const fetch = mockApi({
      'GET /api/me': me('carol@example.com'),
      'GET /api/auth/options': () => json({ googleSignInUrl: null, devLogin: false }),
      'GET /api/invitation': () => json(preview()),
      'POST /api/invitation/accept': () => {
        joined = true
        return json({ ...workspace, role: 'MEMBER' })
      },
      'GET /api/workspaces': () => json(joined ? [{ ...workspace, role: 'MEMBER' }] : []),
      'GET /api/workspaces/ws-1/sessions': () => json({ items: [], page: 0, size: 25, totalItems: 0 }),
      'GET /api/health': health,
    })

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Join Alice Studio' })).toBeTruthy()
    expect(screen.getByText('carol@example.com')).toBeTruthy()
    // The token travels in a header, never in the URL.
    expect(headersOf(fetch, 'GET', '/api/invitation')['X-Invitation-Token']).toBe(TOKEN)
    fireEvent.click(screen.getByRole('button', { name: 'Accept invitation' }))

    expect(await screen.findByRole('heading', { name: 'Sessions' })).toBeTruthy()
    expect(headersOf(fetch, 'POST', '/api/invitation/accept')['X-Invitation-Token']).toBe(TOKEN)
    expect(window.location.hash).toBe('#/sessions')
    expect(window.localStorage.getItem('photospace.selectedWorkspace')).toBe('ws-1')
    expect(window.sessionStorage.getItem('photospace.pendingInvitation')).toBeNull()
  })

  it('remembers the invitation through sign-in', async () => {
    window.history.replaceState(null, '', `/#/invite/${TOKEN}`)
    mockApi({
      'GET /api/me': unauthorized,
      'GET /api/auth/options': () => json({ googleSignInUrl: '/api/auth/oauth2/authorization/google', devLogin: false }),
      'GET /api/health': health,
    })

    render(<App />)

    expect(await screen.findByText('Sign in with the Google account the invitation was sent to.')).toBeTruthy()
    // Google sign-in comes back to "/" without the fragment; the tab still knows the invitation.
    expect(window.sessionStorage.getItem('photospace.pendingInvitation')).toBe(TOKEN)
    cleanup()

    window.history.replaceState(null, '', '/')
    mockApi({
      'GET /api/me': me('carol@example.com'),
      'GET /api/auth/options': () => json({ googleSignInUrl: null, devLogin: false }),
      'GET /api/invitation': () => json(preview()),
      'GET /api/health': health,
    })
    render(<App />)
    expect(await screen.findByRole('button', { name: 'Accept invitation' })).toBeTruthy()
  })

  it('asks a user signed in with another address to switch accounts', async () => {
    window.history.replaceState(null, '', `/#/invite/${TOKEN}`)
    mockApi({
      'GET /api/me': me('eve@example.com'),
      'GET /api/auth/options': () => json({ googleSignInUrl: null, devLogin: false }),
      'GET /api/invitation': () => json(preview({ emailMatches: false })),
      'POST /api/auth/logout': noContent,
      'GET /api/health': health,
    })

    render(<App />)

    expect(await screen.findByText(/You are signed in as eve@example.com/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Accept invitation' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeTruthy()
  })

  it('explains a link that does not work', async () => {
    window.history.replaceState(null, '', `/#/invite/${TOKEN}`)
    mockApi({
      'GET /api/me': me('carol@example.com'),
      'GET /api/auth/options': () => json({ googleSignInUrl: null, devLogin: false }),
      'GET /api/health': health,
    })

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'This invitation link does not work' })).toBeTruthy()
  })

  it('lets owners invite, resend, and revoke from the members page', async () => {
    let invitations = [invitation({ id: 'inv-old', email: 'old@example.com', state: 'EXPIRED', emailStatus: 'FAILED' })]
    const fetch = renderWorkspace('#/members', {
      [`GET ${WS}/members`]: members,
      [`GET ${WS}/invitations`]: () => json(invitations),
      [`POST ${WS}/invitations`]: () => {
        invitations = [invitation(), ...invitations]
        return json(invitations[0], 201)
      },
      [`POST ${WS}/invitations/inv-old/resend`]: () =>
        json({ status: 409, title: 'Conflict', detail: 'This invitation was just sent. Wait a minute before sending it again.' }, 409),
      [`POST ${WS}/invitations/inv-old/revoke`]: () => {
        invitations = invitations.filter((i) => i.id !== 'inv-old')
        return json(invitation({ id: 'inv-old', state: 'REVOKED' }))
      },
    })

    const list = () => screen.findByRole('list', { name: 'Open invitations' })
    expect(within(await list()).getByText('Expired')).toBeTruthy()
    expect(within(await list()).getByText('Email failed')).toBeTruthy()

    fireEvent.change(screen.getByLabelText('Invite someone by email'), { target: { value: ' carol@example.com ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send invitation' }))
    await vi.waitFor(async () => expect(within(await list()).getByText('carol@example.com')).toBeTruthy())
    expect(bodiesOf(fetch, 'POST', `${WS}/invitations`)).toEqual([{ email: 'carol@example.com' }])

    const oldRow = () => within(screen.getByRole('list', { name: 'Open invitations' })).getByText('old@example.com').closest('li') as HTMLElement
    fireEvent.click(within(oldRow()).getByRole('button', { name: 'Resend' }))
    expect(await screen.findByText('This invitation was just sent. Wait a minute before sending it again.')).toBeTruthy()

    fireEvent.click(within(oldRow()).getByRole('button', { name: 'Revoke' }))
    await vi.waitFor(async () => expect(within(await list()).queryByText('old@example.com')).toBeNull())
  })

  it('does not show invitations to members', async () => {
    window.location.hash = '#/members'
    mockApi({
      'GET /api/workspaces': () => json([{ ...workspace, role: 'MEMBER' }]),
      [`GET ${WS}/members`]: members,
    })
    render(<Workspaces userName="carol@example.com" onSignOut={() => {}} />)

    expect(await screen.findByRole('list', { name: 'Members' })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'Invitations' })).toBeNull()
  })
})
