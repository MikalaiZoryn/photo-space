import { useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { getSession } from '../api/sessions.ts'
import { deleteTask, getTask, type Task, type TaskStatus } from '../api/tasks.ts'
import { listMembers } from '../api/workspaces.ts'
import { navigate, paths } from '../routing.ts'
import { formatDate } from '../time.ts'
import { useLoad } from '../useLoad.ts'
import { Button } from '@/components/ui/button'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { CheckboxField } from '../ui/Field.tsx'
import { toFormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { FilterBar } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { TaskForm } from './TaskForm.tsx'
import { TaskList } from './TaskList.tsx'

export function TasksPage({ workspaceId, timezone }: { workspaceId: string; timezone: string }) {
  const [status, setStatus] = useState<TaskStatus | ''>('OPEN')
  const [assigneeId, setAssigneeId] = useState('')
  const [overdue, setOverdue] = useState(false)
  const members = useLoad((signal) => listMembers(workspaceId, signal), [workspaceId])
  const filtered = status !== 'OPEN' || assigneeId !== '' || overdue

  return (
    <section aria-labelledby="tasks-heading">
      <PageHeader
        id="tasks-heading"
        title="Tasks"
        description="Follow-ups for your clients and sessions, by due date."
        actions={
          <Button asChild>
            <a href={paths.taskNew()}>
              <Plus aria-hidden="true" />
              New task
            </a>
          </Button>
        }
      />
      <FilterBar>
        <label className="inline-flex items-center gap-2 text-sm">
          Show{' '}
          <NativeSelect className="bg-card" value={status} onChange={(e) => setStatus(e.target.value as TaskStatus | '')}>
            <NativeSelectOption value="OPEN">Open</NativeSelectOption>
            <NativeSelectOption value="DONE">Done</NativeSelectOption>
            <NativeSelectOption value="">All tasks</NativeSelectOption>
          </NativeSelect>
        </label>
        <label className="inline-flex items-center gap-2 text-sm">
          Assignee{' '}
          <NativeSelect className="bg-card" value={assigneeId} onChange={(e) => setAssigneeId(e.target.value)}>
            <NativeSelectOption value="">Anyone</NativeSelectOption>
            {members.state.kind === 'loaded' &&
              members.state.value.map((m) => (
                <NativeSelectOption key={m.userId} value={m.userId}>
                  {m.displayName}
                </NativeSelectOption>
              ))}
          </NativeSelect>
        </label>
        <CheckboxField label="Overdue only" checked={overdue} onChange={setOverdue} />
        <span className="text-sm text-muted-foreground sm:ml-auto">Dates in {timezone}</span>
      </FilterBar>
      <TaskList
        key={`${status}|${assigneeId}|${overdue}`}
        workspaceId={workspaceId}
        filter={{ status, assigneeId: assigneeId || undefined, overdue }}
        emptyText={filtered ? 'No tasks match these filters.' : 'No open tasks. Create one, or let a rule add them.'}
      />
    </section>
  )
}

/** A new task, optionally for the session given in the URL (from a session's page), linked to its client too. */
export function NewTask({ workspaceId, sessionId }: { workspaceId: string; sessionId: string | null }) {
  const session = useLoad(
    (signal) => (sessionId ? getSession(workspaceId, sessionId, signal) : Promise.resolve(null)),
    [workspaceId, sessionId],
  )
  if (session.state.kind === 'loading') return <Loading>Loading…</Loading>
  if (session.state.kind === 'error') return <LoadError error={session.state.error} what="session" onRetry={session.reload} />
  const linked = session.state.value
  const back = linked ? paths.session(linked.id) : paths.tasks()

  return (
    <section aria-labelledby="new-task-heading">
      <PageHeader
        id="new-task-heading"
        title="New task"
        back={linked ? { href: back, label: 'Back to session' } : { href: back, label: 'All tasks' }}
      />
      <TaskForm
        workspaceId={workspaceId}
        initialSession={
          linked && { id: linked.id, title: linked.title, sessionType: linked.sessionType, archived: linked.archivedAt !== null }
        }
        initialClient={linked?.client ?? null}
        onSaved={() => navigate(back)}
        onCancel={() => navigate(back)}
      />
    </section>
  )
}

export function EditTask({ workspaceId, taskId, timezone }: { workspaceId: string; taskId: string; timezone: string }) {
  const task = useLoad((signal) => getTask(workspaceId, taskId, signal), [workspaceId, taskId])

  if (task.state.kind === 'loading') return <Loading>Loading task…</Loading>
  if (task.state.kind === 'error') return <LoadError error={task.state.error} what="task" onRetry={task.reload} />
  return <TaskEditor workspaceId={workspaceId} task={task.state.value} timezone={timezone} />
}

function TaskEditor({ workspaceId, task, timezone }: { workspaceId: string; task: Task; timezone: string }) {
  const [confirming, setConfirming] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const back = task.session ? paths.session(task.session.id) : paths.tasks()

  async function remove() {
    setDeleting(true)
    setError(null)
    try {
      await deleteTask(workspaceId, task.id)
      navigate(back)
    } catch (e) {
      setError(toFormError(e, 'delete the task').message ?? 'Could not delete the task.')
      setDeleting(false)
    }
  }

  return (
    <section aria-labelledby="edit-task-heading">
      <PageHeader
        id="edit-task-heading"
        title="Edit task"
        back={task.session ? { href: back, label: 'Back to session' } : { href: back, label: 'All tasks' }}
        badges={
          <>
            {task.status === 'DONE' && <StatusBadge tone="success">Done</StatusBadge>}
            {task.overdue && <StatusBadge tone="danger">Overdue</StatusBadge>}
            {task.source === 'AUTOMATION' && <StatusBadge tone="info">Automated</StatusBadge>}
            {task.source === 'INQUIRY' && <StatusBadge tone="info">Inquiry</StatusBadge>}
          </>
        }
        description={
          task.completedAt ? `Completed on ${formatDate(task.completedAt, timezone)}.` : 'Complete it from the task list.'
        }
        actions={
          confirming ? (
            <>
              <Button type="button" variant="destructive" disabled={deleting} onClick={remove}>
                Confirm delete
              </Button>
              <Button type="button" variant="ghost" onClick={() => setConfirming(false)}>
                Keep task
              </Button>
            </>
          ) : (
            <Button type="button" variant="outline" onClick={() => setConfirming(true)}>
              <Trash2 aria-hidden="true" />
              Delete
            </Button>
          )
        }
      />
      {error && (
        <div className="mb-4">
          <ErrorAlert>{error}</ErrorAlert>
        </div>
      )}
      <TaskForm workspaceId={workspaceId} task={task} onSaved={() => navigate(back)} onCancel={() => navigate(back)} />
    </section>
  )
}
