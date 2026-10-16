import { useState, type FormEvent } from 'react'
import { ApiError } from './api/client.ts'
import { createWorkspace, type Workspace } from './api/workspaces.ts'

type Props = {
  onCreated: (workspace: Workspace) => void
  onCancel?: () => void
}

type FormError = { fields: Record<string, string>; message?: string }

export function CreateWorkspaceForm({ onCreated, onCancel }: Props) {
  const [name, setName] = useState('')
  const [timezone, setTimezone] = useState(browserTimezone)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<FormError>({ fields: {} })

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError({ fields: {} })
    try {
      onCreated(await createWorkspace({ name: name.trim(), timezone: timezone.trim() }))
    } catch (e) {
      if (e instanceof ApiError && e.status === 400 && e.problem.errors) {
        setError({ fields: e.problem.errors })
      } else {
        const reference = e instanceof ApiError && e.problem.correlationId ? ` (ref ${e.problem.correlationId})` : ''
        setError({ fields: {}, message: `Could not create the workspace${reference}.` })
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={submit} aria-label="Create workspace" className="stack">
      <label>
        Business name
        <input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      {error.fields.name && <p role="alert">Name {error.fields.name}</p>}
      <label>
        Time zone
        <input required value={timezone} onChange={(e) => setTimezone(e.target.value)} />
      </label>
      {error.fields.timezone && <p role="alert">Time zone {error.fields.timezone}</p>}
      {error.message && <p role="alert">{error.message}</p>}
      <div>
        <button type="submit" disabled={submitting}>
          {submitting ? 'Creating…' : 'Create workspace'}
        </button>{' '}
        {onCancel && (
          <button type="button" onClick={onCancel}>
            Cancel
          </button>
        )}
      </div>
    </form>
  )
}

function browserTimezone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
}
