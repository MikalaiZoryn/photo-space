import type { SessionStatus } from './sessions.ts'
import type { SessionSummary } from './tasks.ts'
import { api, withQuery, type Page } from './client.ts'

/** The statuses a rule can react to. */
export const TRIGGER_STATUSES = ['BOOKED', 'COMPLETED', 'DELIVERED', 'CANCELLED'] as const satisfies readonly SessionStatus[]
export type TriggerStatus = (typeof TRIGGER_STATUSES)[number]

export type Rule = {
  id: string
  triggerStatus: TriggerStatus
  taskTitle: string
  dueInDays: number
  enabled: boolean
  version: number
  createdAt: string
  updatedAt: string
}

export type RuleInput = {
  triggerStatus: TriggerStatus
  taskTitle: string
  dueInDays: number
  enabled: boolean
}

export type RunStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED'

export type Run = {
  id: string
  ruleId: string
  /** What the rule said when it fired. */
  triggerStatus: TriggerStatus
  taskTitle: string
  dueDate: string
  session: SessionSummary | null
  status: RunStatus
  runCount: number
  failureReason: string | null
  /** While pending: the job's attempt count and its last problem, if any. */
  progress: { attempts: number; maxAttempts: number; lastError: string | null } | null
  /** The created task; null until then, or if it was deleted. */
  taskId: string | null
  triggeredAt: string
  finishedAt: string | null
}

export type JobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export type Job = {
  id: string
  type: string
  subjectId: string
  status: JobStatus
  attempts: number
  maxAttempts: number
  lastError: string | null
  createdAt: string
  updatedAt: string
  finishedAt: string | null
}

function automationPath(workspaceId: string) {
  return `/api/workspaces/${encodeURIComponent(workspaceId)}/automation`
}

export function listRules(workspaceId: string, signal?: AbortSignal): Promise<Rule[]> {
  return api<Rule[]>(`${automationPath(workspaceId)}/rules`, { signal })
}

export function createRule(workspaceId: string, input: RuleInput): Promise<Rule> {
  return api<Rule>(`${automationPath(workspaceId)}/rules`, { method: 'POST', body: input })
}

export function updateRule(workspaceId: string, ruleId: string, version: number, input: RuleInput): Promise<Rule> {
  return api<Rule>(`${automationPath(workspaceId)}/rules/${encodeURIComponent(ruleId)}`, {
    method: 'PUT',
    body: { version, rule: input },
  })
}

export function listRuns(
  workspaceId: string,
  query: { status?: RunStatus | ''; page?: number },
  signal?: AbortSignal,
): Promise<Page<Run>> {
  return api<Page<Run>>(withQuery(`${automationPath(workspaceId)}/runs`, query), { signal })
}

export function retryRun(workspaceId: string, runId: string): Promise<Run> {
  return api<Run>(`${automationPath(workspaceId)}/runs/${encodeURIComponent(runId)}/retry`, { method: 'POST' })
}

/** Owners only. */
export function listJobs(
  workspaceId: string,
  query: { status?: JobStatus; page?: number },
  signal?: AbortSignal,
): Promise<Page<Job>> {
  return api<Page<Job>>(withQuery(`/api/workspaces/${encodeURIComponent(workspaceId)}/jobs`, query), { signal })
}
