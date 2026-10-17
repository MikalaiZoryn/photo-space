import { useState } from 'react'
import { Plus, RotateCcw } from 'lucide-react'
import {
  listJobs,
  listRules,
  listRuns,
  retryRun,
  updateRule,
  type Rule,
  type Run,
  type RunStatus,
} from '../api/automation.ts'
import type { WorkspaceRole } from '../api/workspaces.ts'
import { paths } from '../routing.ts'
import { statusLabels } from '../sessions/status.ts'
import { formatDate, formatDay } from '../time.ts'
import { useLoad } from '../useLoad.ts'
import { Button } from '@/components/ui/button'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { EmptyState, ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { toFormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Pager } from '../ui/Pager.tsx'
import { Panel } from '../ui/Panel.tsx'
import { RecordList, RecordRow } from '../ui/RecordList.tsx'
import { StatusBadge, type Tone } from '../ui/StatusBadge.tsx'
import { RuleForm } from './RuleForm.tsx'

type Props = { workspaceId: string; timezone: string; role: WorkspaceRole }

const runLabels: Record<RunStatus, string> = { PENDING: 'Pending', SUCCEEDED: 'Task created', FAILED: 'Failed' }
const runTones: Record<RunStatus, Tone> = { PENDING: 'warning', SUCCEEDED: 'success', FAILED: 'danger' }

export function AutomationPage({ workspaceId, timezone, role }: Props) {
  const owner = role === 'OWNER'
  return (
    <section aria-labelledby="automation-heading">
      <PageHeader
        id="automation-heading"
        title="Automation"
        description="Rules that add tasks when a session changes status. Each rule fires at most once per session."
        back={{ href: paths.settings(), label: 'Settings' }}
      />
      <div className="space-y-6">
        <RulesPanel workspaceId={workspaceId} owner={owner} />
        <RunsPanel workspaceId={workspaceId} timezone={timezone} />
        {owner && <FailedJobsPanel workspaceId={workspaceId} timezone={timezone} />}
      </div>
    </section>
  )
}

function RulesPanel({ workspaceId, owner }: { workspaceId: string; owner: boolean }) {
  const rules = useLoad((signal) => listRules(workspaceId, signal), [workspaceId])
  const [editing, setEditing] = useState<string | 'new' | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  function saved(rule: Rule) {
    if (rules.state.kind === 'loaded') {
      const current = rules.state.value
      rules.replace(current.some((r) => r.id === rule.id) ? current.map((r) => (r.id === rule.id ? rule : r)) : [...current, rule])
    }
    setEditing(null)
  }

  async function toggle(rule: Rule) {
    setBusyId(rule.id)
    setError(null)
    try {
      const { triggerStatus, taskTitle, dueInDays } = rule
      saved(await updateRule(workspaceId, rule.id, rule.version, { triggerStatus, taskTitle, dueInDays, enabled: !rule.enabled }))
    } catch (e) {
      setError(toFormError(e, 'change the rule').message ?? 'Could not change the rule.')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <Panel
      id="rules-heading"
      title="Rules"
      actions={
        owner &&
        editing !== 'new' && (
          <Button type="button" variant="outline" size="sm" onClick={() => setEditing('new')}>
            <Plus aria-hidden="true" />
            Add rule
          </Button>
        )
      }
    >
      {!owner && <p className="text-sm text-muted-foreground">Only workspace owners can change rules.</p>}
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {editing === 'new' && <RuleForm workspaceId={workspaceId} onSaved={saved} onCancel={() => setEditing(null)} />}
      {rules.state.kind === 'loading' && <Loading>Loading rules…</Loading>}
      {rules.state.kind === 'error' && <LoadError error={rules.state.error} what="rules" onRetry={rules.reload} />}
      {rules.state.kind === 'loaded' && rules.state.value.length === 0 && editing !== 'new' && (
        <EmptyState>No rules yet. For example: when a session is completed, create “Send the gallery” due in 3 days.</EmptyState>
      )}
      {rules.state.kind === 'loaded' && rules.state.value.length > 0 && (
        <RecordList label="Rules">
          {rules.state.value.map((rule) =>
            editing === rule.id ? (
              <li key={rule.id} className="p-3">
                <RuleForm workspaceId={workspaceId} rule={rule} onSaved={saved} onCancel={() => setEditing(null)} />
              </li>
            ) : (
              <RecordRow
                key={rule.id}
                title={rule.taskTitle}
                meta={`When a session is marked ${statusLabels[rule.triggerStatus].toLowerCase()} · due ${
                  rule.dueInDays === 0 ? 'the same day' : `${rule.dueInDays} day${rule.dueInDays === 1 ? '' : 's'} later`
                }`}
                trailing={
                  <>
                    <StatusBadge tone={rule.enabled ? 'success' : 'neutral'}>{rule.enabled ? 'Enabled' : 'Disabled'}</StatusBadge>
                    {owner && (
                      <>
                        <Button type="button" variant="ghost" size="sm" disabled={busyId === rule.id} onClick={() => toggle(rule)}>
                          {rule.enabled ? 'Disable' : 'Enable'}
                        </Button>
                        <Button type="button" variant="ghost" size="sm" onClick={() => setEditing(rule.id)}>
                          Edit
                        </Button>
                      </>
                    )}
                  </>
                }
              />
            ),
          )}
        </RecordList>
      )}
    </Panel>
  )
}

function RunsPanel({ workspaceId, timezone }: { workspaceId: string; timezone: string }) {
  const [status, setStatus] = useState<RunStatus | ''>('')
  const [page, setPage] = useState(0)
  const runs = useLoad((signal) => listRuns(workspaceId, { status, page }, signal), [workspaceId, status, page])
  const [busyId, setBusyId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function retry(run: Run) {
    if (runs.state.kind !== 'loaded') return
    const result = runs.state.value
    setBusyId(run.id)
    setError(null)
    try {
      const updated = await retryRun(workspaceId, run.id)
      runs.replace({ ...result, items: result.items.map((r) => (r.id === updated.id ? updated : r)) })
    } catch (e) {
      setError(toFormError(e, 'retry the run').message ?? 'Could not retry the run.')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <Panel
      id="runs-heading"
      title="Recent runs"
      actions={
        <>
          <label className="inline-flex items-center gap-2 text-sm">
            Status{' '}
            <NativeSelect
              size="sm"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value as RunStatus | '')
                setPage(0)
              }}
            >
              <NativeSelectOption value="">All</NativeSelectOption>
              <NativeSelectOption value="PENDING">Pending</NativeSelectOption>
              <NativeSelectOption value="SUCCEEDED">Task created</NativeSelectOption>
              <NativeSelectOption value="FAILED">Failed</NativeSelectOption>
            </NativeSelect>
          </label>
          <Button type="button" variant="ghost" size="sm" onClick={runs.reload}>
            <RotateCcw aria-hidden="true" />
            Refresh
          </Button>
        </>
      }
    >
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {runs.state.kind === 'loading' && <Loading>Loading runs…</Loading>}
      {runs.state.kind === 'error' && <LoadError error={runs.state.error} what="runs" onRetry={runs.reload} />}
      {runs.state.kind === 'loaded' && runs.state.value.totalItems === 0 && (
        <EmptyState>{status ? 'No runs with this status.' : 'No rule has fired yet.'}</EmptyState>
      )}
      {runs.state.kind === 'loaded' && runs.state.value.totalItems > 0 && (
        <>
          <RecordList label="Runs">
            {runs.state.value.items.map((run) => (
              <RecordRow
                key={run.id}
                title={run.taskTitle}
                meta={
                  <>
                    {[
                      run.session ? `${run.session.title} marked ${statusLabels[run.triggerStatus].toLowerCase()}` : null,
                      `on ${formatDate(run.triggeredAt, timezone)}`,
                      `due ${formatDay(run.dueDate)}`,
                      run.runCount > 1 ? `run ${run.runCount}` : null,
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                    {run.progress && run.progress.attempts > 0 && (
                      <span className="block">
                        Attempt {run.progress.attempts} of {run.progress.maxAttempts}
                        {run.progress.lastError ? `: ${run.progress.lastError}` : ''}
                      </span>
                    )}
                    {run.failureReason && <span className="block text-tone-danger">{run.failureReason}</span>}
                  </>
                }
                trailing={
                  <>
                    <StatusBadge tone={runTones[run.status]}>{runLabels[run.status]}</StatusBadge>
                    {run.taskId && (
                      <Button asChild variant="ghost" size="sm">
                        <a href={paths.taskEdit(run.taskId)}>Open task</a>
                      </Button>
                    )}
                    {run.status === 'FAILED' && (
                      <Button type="button" variant="outline" size="sm" disabled={busyId === run.id} onClick={() => retry(run)}>
                        Retry
                      </Button>
                    )}
                  </>
                }
              />
            ))}
          </RecordList>
          <Pager page={runs.state.value} onPage={setPage} />
        </>
      )}
    </Panel>
  )
}

const jobLabels: Record<string, string> = {
  'process-asset': 'Photo processing',
  'generate-content': 'Content drafts',
  'run-automation': 'Automation run',
}

/** Owners only: background jobs that ran out of attempts. Recovery happens where the work belongs. */
function FailedJobsPanel({ workspaceId, timezone }: { workspaceId: string; timezone: string }) {
  const [page, setPage] = useState(0)
  const jobs = useLoad((signal) => listJobs(workspaceId, { status: 'FAILED', page }, signal), [workspaceId, page])

  return (
    <Panel id="failed-jobs-heading" title="Failed background jobs">
      <p className="text-sm text-muted-foreground">
        Jobs that failed after every retry. Retry automation runs above, retry content drafts from their batch, and upload
        a failed photo again.
      </p>
      {jobs.state.kind === 'loading' && <Loading>Loading jobs…</Loading>}
      {jobs.state.kind === 'error' && <LoadError error={jobs.state.error} what="jobs" onRetry={jobs.reload} />}
      {jobs.state.kind === 'loaded' && jobs.state.value.totalItems === 0 && <EmptyState>No failed jobs.</EmptyState>}
      {jobs.state.kind === 'loaded' && jobs.state.value.totalItems > 0 && (
        <>
          <RecordList label="Failed jobs">
            {jobs.state.value.items.map((job) => (
              <RecordRow
                key={job.id}
                title={jobLabels[job.type] ?? job.type}
                meta={[
                  job.lastError,
                  `${job.attempts} of ${job.maxAttempts} attempts`,
                  job.finishedAt ? `failed on ${formatDate(job.finishedAt, timezone)}` : null,
                ]
                  .filter(Boolean)
                  .join(' · ')}
                trailing={<StatusBadge tone="danger">Failed</StatusBadge>}
              />
            ))}
          </RecordList>
          <Pager page={jobs.state.value} onPage={setPage} />
        </>
      )}
    </Panel>
  )
}
