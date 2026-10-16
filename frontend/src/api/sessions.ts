import type { ClientSummary } from './clients.ts'
import { api, withQuery, type Page } from './client.ts'

export const SESSION_STATUSES = ['INQUIRY', 'BOOKED', 'COMPLETED', 'DELIVERED', 'CANCELLED'] as const
export type SessionStatus = (typeof SESSION_STATUSES)[number]

export type PhotoSession = {
  id: string
  title: string
  sessionType: string | null
  status: SessionStatus
  /** The statuses the server allows changing to now; empty when archived or delivered. */
  allowedStatuses: SessionStatus[]
  client: ClientSummary | null
  /** UTC instants. */
  startsAt: string | null
  endsAt: string | null
  /** The same moments as wall-clock times in `timezone`, e.g. "2026-07-04T10:00:00". */
  startLocal: string | null
  endLocal: string | null
  timezone: string
  location: string | null
  notes: string | null
  archivedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

/** Start and end are wall-clock times in the workspace time zone ("2026-07-04T10:00"); the server converts them. */
export type SessionInput = {
  title: string
  sessionType: string | null
  clientId: string | null
  startLocal: string | null
  endLocal: string | null
  location: string | null
  notes: string | null
}

export type SessionQuery = {
  status?: SessionStatus | ''
  clientId?: string
  archived?: boolean
  page?: number
  size?: number
}

function sessionsPath(workspaceId: string) {
  return `/api/workspaces/${encodeURIComponent(workspaceId)}/sessions`
}

function sessionPath(workspaceId: string, sessionId: string) {
  return `${sessionsPath(workspaceId)}/${encodeURIComponent(sessionId)}`
}

export function listSessions(workspaceId: string, query: SessionQuery, signal?: AbortSignal): Promise<Page<PhotoSession>> {
  return api<Page<PhotoSession>>(withQuery(sessionsPath(workspaceId), query), { signal })
}

export function getSession(workspaceId: string, sessionId: string, signal?: AbortSignal): Promise<PhotoSession> {
  return api<PhotoSession>(sessionPath(workspaceId, sessionId), { signal })
}

/** New sessions always start as inquiries; booking is a status change. */
export function createSession(workspaceId: string, input: SessionInput): Promise<PhotoSession> {
  return api<PhotoSession>(sessionsPath(workspaceId), { method: 'POST', body: input })
}

export function updateSession(
  workspaceId: string,
  sessionId: string,
  version: number,
  input: SessionInput,
): Promise<PhotoSession> {
  return api<PhotoSession>(sessionPath(workspaceId, sessionId), { method: 'PUT', body: { version, session: input } })
}

export function changeSessionStatus(workspaceId: string, sessionId: string, status: SessionStatus): Promise<PhotoSession> {
  return api<PhotoSession>(`${sessionPath(workspaceId, sessionId)}/status`, { method: 'POST', body: { status } })
}

export function setSessionArchived(workspaceId: string, sessionId: string, archived: boolean): Promise<PhotoSession> {
  return api<PhotoSession>(`${sessionPath(workspaceId, sessionId)}/${archived ? 'archive' : 'restore'}`, {
    method: 'POST',
  })
}
