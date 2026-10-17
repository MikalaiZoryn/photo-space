import { api } from './client.ts'
import type { Workspace, WorkspaceRole } from './workspaces.ts'

/** `EXPIRED` is an open invitation past its expiry; resending opens it again. */
export type InvitationState = 'PENDING' | 'EXPIRED' | 'ACCEPTED' | 'REVOKED'
export type EmailStatus = 'QUEUED' | 'SENT' | 'FAILED'

export type Invitation = {
  id: string
  email: string
  role: WorkspaceRole
  state: InvitationState
  emailStatus: EmailStatus
  expiresAt: string
  emailSentAt: string | null
  createdAt: string
}

/** An invitation as the invited person sees it, through the link in their email. */
export type InvitationPreview = {
  workspaceName: string
  invitedBy: string | null
  email: string
  state: InvitationState
  expiresAt: string
  /** Whether the signed-in user's verified email is the invited address, so they can accept. */
  emailMatches: boolean
  alreadyMember: boolean
}

const base = (workspaceId: string) => `/api/workspaces/${encodeURIComponent(workspaceId)}/invitations`

export function listInvitations(workspaceId: string, signal?: AbortSignal): Promise<Invitation[]> {
  return api<Invitation[]>(base(workspaceId), { signal })
}

export function createInvitation(workspaceId: string, email: string): Promise<Invitation> {
  return api<Invitation>(base(workspaceId), { method: 'POST', body: { email } })
}

export function resendInvitation(workspaceId: string, id: string): Promise<Invitation> {
  return api<Invitation>(`${base(workspaceId)}/${encodeURIComponent(id)}/resend`, { method: 'POST' })
}

export function revokeInvitation(workspaceId: string, id: string): Promise<Invitation> {
  return api<Invitation>(`${base(workspaceId)}/${encodeURIComponent(id)}/revoke`, { method: 'POST' })
}

// --- The invited person: the token from the email, sent in a header and never in a URL. ---

const TOKEN_HEADER = 'X-Invitation-Token'

export function fetchInvitation(token: string, signal?: AbortSignal): Promise<InvitationPreview> {
  return api<InvitationPreview>('/api/invitation', { signal, headers: { [TOKEN_HEADER]: token } })
}

/** Joins the workspace; returns it as the new member sees it. */
export function acceptInvitation(token: string): Promise<Workspace> {
  return api<Workspace>('/api/invitation/accept', { method: 'POST', headers: { [TOKEN_HEADER]: token } })
}
