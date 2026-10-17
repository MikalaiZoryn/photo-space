import { useState, type FormEvent } from 'react'
import type { ClientSummary } from '../api/clients.ts'
import { createTask, updateTask, type SessionSummary, type Task, type TaskInput } from '../api/tasks.ts'
import { listMembers } from '../api/workspaces.ts'
import { paths } from '../routing.ts'
import { ClientPicker } from '../sessions/ClientPicker.tsx'
import { useLoad } from '../useLoad.ts'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { ErrorText, Field, formCard, FormActions } from '../ui/Field.tsx'

type Props = {
  workspaceId: string
  /** The task to edit; omitted to create one. */
  task?: Task
  /** For a new task started from a session's page. */
  initialSession?: SessionSummary | null
  initialClient?: ClientSummary | null
  onSaved: (task: Task) => void
  onCancel: () => void
}

export function TaskForm({ workspaceId, task, initialSession = null, initialClient = null, onSaved, onCancel }: Props) {
  const [title, setTitle] = useState(task?.title ?? '')
  const [dueDate, setDueDate] = useState(task?.dueDate ?? '')
  const [assigneeId, setAssigneeId] = useState(task?.assignee?.userId ?? '')
  const [description, setDescription] = useState(task?.description ?? '')
  const [client, setClient] = useState<ClientSummary | null>(task ? task.client : initialClient)
  const [session, setSession] = useState<SessionSummary | null>(task ? task.session : initialSession)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)
  const members = useLoad((signal) => listMembers(workspaceId, signal), [workspaceId])

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(noFormError)
    const input: TaskInput = {
      title: title.trim(),
      description: description.trim() || null,
      dueDate: dueDate || null,
      clientId: client?.id ?? null,
      sessionId: session?.id ?? null,
      assigneeId: assigneeId || null,
    }
    try {
      onSaved(task ? await updateTask(workspaceId, task.id, task.version, input) : await createTask(workspaceId, input))
    } catch (e) {
      setError(toFormError(e, task ? 'save the task' : 'create the task'))
      setSubmitting(false)
    }
  }

  const memberOptions = members.state.kind === 'loaded' ? members.state.value : []
  const showCurrentAssignee =
    task?.assignee != null && !memberOptions.some((m) => m.userId === task.assignee?.userId)

  return (
    <form onSubmit={submit} aria-label={task ? 'Edit task' : 'New task'} className={formCard}>
      <Field label="Title" className="md:col-span-2" error={error.fields.title && `Title ${error.fields.title}`}>
        <Input required maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} />
      </Field>
      <Field label="Due date" error={error.fields.dueDate && `Due date ${error.fields.dueDate}`}>
        <Input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
      </Field>
      <Field label="Assignee" error={error.fields.assigneeId && `Assignee ${error.fields.assigneeId}`}>
        <NativeSelect className="w-full" value={assigneeId} onChange={(e) => setAssigneeId(e.target.value)}>
          <NativeSelectOption value="">Nobody</NativeSelectOption>
          {showCurrentAssignee && task?.assignee && (
            <NativeSelectOption value={task.assignee.userId}>{task.assignee.displayName}</NativeSelectOption>
          )}
          {memberOptions.map((m) => (
            <NativeSelectOption key={m.userId} value={m.userId}>
              {m.displayName}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </Field>
      <Field label="Notes" className="md:col-span-2" error={error.fields.description && `Notes ${error.fields.description}`}>
        <Textarea rows={3} maxLength={10000} value={description} onChange={(e) => setDescription(e.target.value)} />
      </Field>
      <div className="md:col-span-2">
        <ClientPicker workspaceId={workspaceId} value={client} onChange={setClient} />
        {error.fields.clientId && <ErrorText>Client {error.fields.clientId}</ErrorText>}
      </div>
      {session && (
        <div className="flex flex-wrap items-center gap-3 text-sm md:col-span-2">
          <span className="font-semibold">Session</span>
          <a href={paths.session(session.id)}>{session.title}</a>
          <Button type="button" variant="ghost" size="sm" onClick={() => setSession(null)}>
            Unlink
          </Button>
          {error.fields.sessionId && <ErrorText>Session {error.fields.sessionId}</ErrorText>}
        </div>
      )}
      {error.message && (
        <div className="md:col-span-2">
          <ErrorText>{error.message}</ErrorText>
        </div>
      )}
      <FormActions>
        <Button type="submit" disabled={submitting}>
          {submitting ? 'Saving…' : task ? 'Save task' : 'Create task'}
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
      </FormActions>
    </form>
  )
}
