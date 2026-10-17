import { api, withQuery, type Page } from './client.ts'

export type AuditEvent = {
  id: string
  action: string
  targetType: string
  targetId: string
  /** A short, code-built detail such as "INQUIRY to BOOKED". */
  detail: string | null
  /** Null when the system made the change, such as an automation rule. */
  actor: { userId: string; displayName: string } | null
  correlationId: string | null
  createdAt: string
}

/** Owners only. */
export function listAuditEvents(
  workspaceId: string,
  query: { page?: number; size?: number },
  signal?: AbortSignal,
): Promise<Page<AuditEvent>> {
  return api<Page<AuditEvent>>(withQuery(`/api/workspaces/${encodeURIComponent(workspaceId)}/audit-events`, query), {
    signal,
  })
}
