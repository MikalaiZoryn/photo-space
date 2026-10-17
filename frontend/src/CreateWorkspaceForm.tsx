import { useState, type FormEvent } from 'react'
import { createWorkspace, type Workspace } from './api/workspaces.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ErrorText, Field } from './ui/Field.tsx'
import { noFormError, toFormError, type FormError } from './ui/formError.ts'

type Props = {
  onCreated: (workspace: Workspace) => void
  onCancel?: () => void
}

export function CreateWorkspaceForm({ onCreated, onCancel }: Props) {
  const [name, setName] = useState('')
  const [timezone, setTimezone] = useState(browserTimezone)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(noFormError)
    try {
      onCreated(await createWorkspace({ name: name.trim(), timezone: timezone.trim() }))
    } catch (e) {
      setError(toFormError(e, 'create the workspace'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={submit} aria-label="Create workspace" className="grid gap-4">
      <Field label="Business name" error={error.fields.name && `Name ${error.fields.name}`}>
        <Input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label="Time zone" error={error.fields.timezone && `Time zone ${error.fields.timezone}`}>
        <Input required value={timezone} onChange={(e) => setTimezone(e.target.value)} />
      </Field>
      {error.message && <ErrorText>{error.message}</ErrorText>}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={submitting}>
          {submitting ? 'Creating…' : 'Create workspace'}
        </Button>
        {onCancel && (
          <Button type="button" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  )
}

function browserTimezone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
}
