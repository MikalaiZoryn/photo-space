import { useState } from 'react'
import { listTasks, setTaskStatus, type Task, type TaskQuery } from '../api/tasks.ts'
import { paths } from '../routing.ts'
import { formatDay } from '../time.ts'
import { EmptyState, ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { toFormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { Pager } from '../ui/Pager.tsx'
import { RecordList, RecordRow } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { useLoad } from '../useLoad.ts'

type Props = {
  workspaceId: string
  /** Fixed for the life of the component; give it a `key` that changes with the filter to start at page one. */
  filter: Omit<TaskQuery, 'page' | 'size'>
  emptyText: string
  /** Leave out the session in each row, e.g. on the session's own page. */
  hideSession?: boolean
}

/**
 * A paged list of tasks, open ones first by due date. Each row has a checkbox that completes or reopens the task;
 * the row stays in place until the next load, even if it no longer matches the filter.
 */
export function TaskList({ workspaceId, filter, emptyText, hideSession = false }: Props) {
  const [page, setPage] = useState(0)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { status, clientId, sessionId, assigneeId, overdue } = filter
  const tasks = useLoad(
    (signal) => listTasks(workspaceId, { status, clientId, sessionId, assigneeId, overdue, page }, signal),
    [workspaceId, status, clientId, sessionId, assigneeId, overdue, page],
  )

  if (tasks.state.kind === 'loading') return <Loading>Loading tasks…</Loading>
  if (tasks.state.kind === 'error') return <LoadError error={tasks.state.error} what="tasks" onRetry={tasks.reload} />
  const result = tasks.state.value
  if (result.totalItems === 0) return <EmptyState>{emptyText}</EmptyState>

  async function toggle(task: Task) {
    setBusyId(task.id)
    setError(null)
    try {
      const saved = await setTaskStatus(workspaceId, task.id, task.status === 'DONE' ? 'OPEN' : 'DONE')
      tasks.replace({ ...result, items: result.items.map((t) => (t.id === saved.id ? saved : t)) })
    } catch (e) {
      setError(toFormError(e, 'update the task').message ?? 'Could not update the task.')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="space-y-3">
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <RecordList label="Tasks">
        {result.items.map((task) => (
          <RecordRow
            key={task.id}
            href={paths.taskEdit(task.id)}
            leading={
              <input
                type="checkbox"
                aria-label={`${task.status === 'DONE' ? 'Reopen' : 'Complete'} ${task.title}`}
                className="relative z-10 size-4.5 rounded accent-brand"
                checked={task.status === 'DONE'}
                disabled={busyId === task.id}
                onChange={() => toggle(task)}
              />
            }
            title={<span className={task.status === 'DONE' ? 'text-muted-foreground line-through' : undefined}>{task.title}</span>}
            meta={[
              task.dueDate ? `Due ${formatDay(task.dueDate)}` : 'No due date',
              hideSession ? null : task.session?.title,
              task.client?.name,
              task.assignee ? `Assigned to ${task.assignee.displayName}` : null,
            ]
              .filter(Boolean)
              .join(' · ')}
            trailing={
              <>
                {task.overdue && <StatusBadge tone="danger">Overdue</StatusBadge>}
                {task.status === 'DONE' && <StatusBadge tone="success">Done</StatusBadge>}
                {task.source === 'AUTOMATION' && <StatusBadge tone="info">Automated</StatusBadge>}
              </>
            }
          />
        ))}
      </RecordList>
      <Pager page={result} onPage={setPage} />
    </div>
  )
}
