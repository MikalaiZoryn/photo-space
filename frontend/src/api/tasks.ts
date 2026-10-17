import type { ClientSummary } from './clients.ts'
import { api, withQuery, type Page } from './client.ts'

export type TaskStatus = 'OPEN' | 'DONE'

/** What a task shows about its linked session. */
export type SessionSummary = {
  id: string
  title: string
  sessionType: string | null
  archived: boolean
}

export type Task = {
  id: string
  title: string
  description: string | null
  /** A calendar date in the workspace time zone, e.g. "2026-11-07". */
  dueDate: string | null
  /** Open and due before today in the workspace time zone. */
  overdue: boolean
  status: TaskStatus
  client: ClientSummary | null
  session: SessionSummary | null
  assignee: { userId: string; displayName: string } | null
  source: 'MANUAL' | 'AUTOMATION' | 'INQUIRY'
  completedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export type TaskInput = {
  title: string
  description: string | null
  dueDate: string | null
  clientId: string | null
  sessionId: string | null
  assigneeId: string | null
}

export type TaskQuery = {
  status?: TaskStatus | ''
  clientId?: string
  sessionId?: string
  assigneeId?: string
  overdue?: boolean
  page?: number
  size?: number
}

function tasksPath(workspaceId: string) {
  return `/api/workspaces/${encodeURIComponent(workspaceId)}/tasks`
}

function taskPath(workspaceId: string, taskId: string) {
  return `${tasksPath(workspaceId)}/${encodeURIComponent(taskId)}`
}

export function listTasks(workspaceId: string, query: TaskQuery, signal?: AbortSignal): Promise<Page<Task>> {
  // "overdue=false" is the default; leave it out of the URL.
  return api<Page<Task>>(withQuery(tasksPath(workspaceId), { ...query, overdue: query.overdue || undefined }), {
    signal,
  })
}

export function getTask(workspaceId: string, taskId: string, signal?: AbortSignal): Promise<Task> {
  return api<Task>(taskPath(workspaceId, taskId), { signal })
}

export function createTask(workspaceId: string, input: TaskInput): Promise<Task> {
  return api<Task>(tasksPath(workspaceId), { method: 'POST', body: input })
}

export function updateTask(workspaceId: string, taskId: string, version: number, input: TaskInput): Promise<Task> {
  return api<Task>(taskPath(workspaceId, taskId), { method: 'PUT', body: { version, task: input } })
}

export function setTaskStatus(workspaceId: string, taskId: string, status: TaskStatus): Promise<Task> {
  return api<Task>(`${taskPath(workspaceId, taskId)}/status`, { method: 'POST', body: { status } })
}

export function deleteTask(workspaceId: string, taskId: string): Promise<void> {
  return api<void>(taskPath(workspaceId, taskId), { method: 'DELETE' })
}
