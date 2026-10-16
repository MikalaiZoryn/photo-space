import { api } from './client.ts'

export type WorkspaceRole = 'OWNER' | 'MEMBER'

export type Workspace = {
  id: string
  name: string
  timezone: string
  role: WorkspaceRole
  createdAt: string
}

export type Member = {
  userId: string
  displayName: string
  role: WorkspaceRole
  joinedAt: string
}

export type WorkspaceInput = {
  name: string
  timezone: string
}

export function listWorkspaces(signal?: AbortSignal): Promise<Workspace[]> {
  return api<Workspace[]>('/api/workspaces', { signal })
}

export function createWorkspace(input: WorkspaceInput): Promise<Workspace> {
  return api<Workspace>('/api/workspaces', { method: 'POST', body: input })
}

export function listMembers(workspaceId: string, signal?: AbortSignal): Promise<Member[]> {
  return api<Member[]>(`/api/workspaces/${encodeURIComponent(workspaceId)}/members`, { signal })
}
