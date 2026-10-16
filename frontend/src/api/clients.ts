import { api, withQuery, type Page } from './client.ts'

export type Client = {
  id: string
  name: string
  email: string | null
  phone: string | null
  notes: string | null
  archivedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

/** What a session shows about its linked client. */
export type ClientSummary = {
  id: string
  name: string
  archived: boolean
}

export type ClientInput = {
  name: string
  email: string | null
  phone: string | null
  notes: string | null
}

export type ClientQuery = {
  q?: string
  archived?: boolean
  page?: number
  size?: number
}

function clientsPath(workspaceId: string) {
  return `/api/workspaces/${encodeURIComponent(workspaceId)}/clients`
}

function clientPath(workspaceId: string, clientId: string) {
  return `${clientsPath(workspaceId)}/${encodeURIComponent(clientId)}`
}

export function listClients(workspaceId: string, query: ClientQuery, signal?: AbortSignal): Promise<Page<Client>> {
  return api<Page<Client>>(withQuery(clientsPath(workspaceId), query), { signal })
}

export function getClient(workspaceId: string, clientId: string, signal?: AbortSignal): Promise<Client> {
  return api<Client>(clientPath(workspaceId, clientId), { signal })
}

export function createClient(workspaceId: string, input: ClientInput): Promise<Client> {
  return api<Client>(clientsPath(workspaceId), { method: 'POST', body: input })
}

/** Saves the edit only if nobody changed the client since `version` was read; otherwise the server returns 409. */
export function updateClient(workspaceId: string, clientId: string, version: number, input: ClientInput): Promise<Client> {
  return api<Client>(clientPath(workspaceId, clientId), { method: 'PUT', body: { version, client: input } })
}

export function setClientArchived(workspaceId: string, clientId: string, archived: boolean): Promise<Client> {
  return api<Client>(`${clientPath(workspaceId, clientId)}/${archived ? 'archive' : 'restore'}`, { method: 'POST' })
}

export function summaryOf(client: Client): ClientSummary {
  return { id: client.id, name: client.name, archived: client.archivedAt !== null }
}
