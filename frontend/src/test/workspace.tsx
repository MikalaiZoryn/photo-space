import { render } from '@testing-library/react'
import { Workspaces } from '../Workspaces.tsx'
import { json, mockApi } from './mockApi.ts'

export const WS = '/api/workspaces/ws-1'

export const workspace = {
  id: 'ws-1',
  name: 'Alice Studio',
  timezone: 'Europe/Warsaw',
  role: 'OWNER',
  createdAt: '2026-10-15T10:00:00Z',
}

export function page<T>(items: T[], overrides: { page?: number; size?: number; totalItems?: number } = {}) {
  return json({ items, page: 0, size: 25, totalItems: items.length, ...overrides })
}

export function client(overrides: Record<string, unknown> = {}) {
  return {
    id: 'c-1',
    name: 'Anna Nowak',
    email: 'anna@example.com',
    phone: null,
    notes: null,
    archivedAt: null,
    version: 0,
    createdAt: '2026-10-15T10:00:00Z',
    updatedAt: '2026-10-15T10:00:00Z',
    ...overrides,
  }
}

export function session(overrides: Record<string, unknown> = {}) {
  return {
    id: 's-1',
    title: 'Family session',
    sessionType: 'Family',
    status: 'INQUIRY',
    allowedStatuses: ['BOOKED', 'CANCELLED'],
    client: { id: 'c-1', name: 'Anna Nowak', archived: false },
    startsAt: '2026-07-04T08:00:00Z',
    endsAt: null,
    startLocal: '2026-07-04T10:00:00',
    endLocal: null,
    timezone: 'Europe/Warsaw',
    location: 'Łazienki Park',
    notes: null,
    archivedAt: null,
    version: 0,
    createdAt: '2026-10-15T10:00:00Z',
    updatedAt: '2026-10-15T10:00:00Z',
    ...overrides,
  }
}

/** Renders the signed-in workspace area for one workspace ("Alice Studio", Europe/Warsaw) at `hash`. */
export function renderWorkspace(hash: string, routes: Parameters<typeof mockApi>[0]) {
  window.location.hash = hash
  const fetch = mockApi({ 'GET /api/workspaces': () => json([workspace]), ...routes })
  render(<Workspaces userId="u-1" userName="Alice" onSignOut={() => {}} />)
  return fetch
}
