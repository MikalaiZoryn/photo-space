import { useState, type FormEvent } from 'react'
import type { ClientSummary } from '../api/clients.ts'
import { createSession, updateSession, type PhotoSession, type SessionInput } from '../api/sessions.ts'
import { toInputValue } from '../time.ts'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { ErrorText, Field, formCard, FormActions } from '../ui/Field.tsx'
import { ClientPicker } from './ClientPicker.tsx'

type Props = {
  workspaceId: string
  /** Start and end are entered as wall-clock times in this zone. */
  timezone: string
  /** The session to edit; omitted to create one. */
  session?: PhotoSession
  initialClient?: ClientSummary | null
  onSaved: (session: PhotoSession) => void
  onCancel: () => void
}

const SESSION_TYPES = ['Wedding', 'Engagement', 'Family', 'Newborn', 'Maternity', 'Portrait', 'Event', 'Commercial']

export function SessionForm({ workspaceId, timezone, session, initialClient = null, onSaved, onCancel }: Props) {
  const [title, setTitle] = useState(session?.title ?? '')
  const [sessionType, setSessionType] = useState(session?.sessionType ?? '')
  const [client, setClient] = useState<ClientSummary | null>(session ? session.client : initialClient)
  const [start, setStart] = useState(toInputValue(session?.startLocal ?? null))
  const [end, setEnd] = useState(toInputValue(session?.endLocal ?? null))
  const [location, setLocation] = useState(session?.location ?? '')
  const [notes, setNotes] = useState(session?.notes ?? '')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)
  // Editing a session in the display zone it was loaded with; the server answers in the current workspace zone.
  const zone = session?.timezone ?? timezone

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(noFormError)
    const input: SessionInput = {
      title: title.trim(),
      sessionType: sessionType.trim() || null,
      clientId: client?.id ?? null,
      startLocal: start || null,
      endLocal: end || null,
      location: location.trim() || null,
      notes: notes.trim() || null,
    }
    try {
      onSaved(
        session
          ? await updateSession(workspaceId, session.id, session.version, input)
          : await createSession(workspaceId, input),
      )
    } catch (e) {
      setError(toFormError(e, session ? 'save the session' : 'create the session'))
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={submit} aria-label={session ? 'Edit session' : 'New session'} className={formCard}>
      <Field label="Title" error={error.fields.title && `Title ${error.fields.title}`}>
        <Input required maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} />
      </Field>
      <Field label="Session type" error={error.fields.sessionType && `Session type ${error.fields.sessionType}`}>
        <Input list="session-types" maxLength={100} value={sessionType} onChange={(e) => setSessionType(e.target.value)} />
        <datalist id="session-types">
          {SESSION_TYPES.map((t) => (
            <option key={t} value={t} />
          ))}
        </datalist>
      </Field>
      <div className="md:col-span-2">
        <ClientPicker workspaceId={workspaceId} value={client} onChange={setClient} />
        {error.fields.clientId && <ErrorText>Client {error.fields.clientId}</ErrorText>}
      </div>
      <fieldset className="grid gap-4 md:col-span-2 md:grid-cols-2">
        <legend className="mb-2 text-sm font-semibold">When (times in {zone})</legend>
        <Field label="Start" error={error.fields.startLocal && `Start ${error.fields.startLocal}`}>
          <Input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} />
        </Field>
        <Field label="End" error={error.fields.endLocal && `End ${error.fields.endLocal}`}>
          <Input type="datetime-local" value={end} min={start || undefined} onChange={(e) => setEnd(e.target.value)} />
        </Field>
      </fieldset>
      <Field label="Location" className="md:col-span-2" error={error.fields.location && `Location ${error.fields.location}`}>
        <Input maxLength={300} value={location} onChange={(e) => setLocation(e.target.value)} />
      </Field>
      <Field label="Notes" className="md:col-span-2" error={error.fields.notes && `Notes ${error.fields.notes}`}>
        <Textarea rows={4} maxLength={10000} value={notes} onChange={(e) => setNotes(e.target.value)} />
      </Field>
      {error.message && (
        <div className="md:col-span-2">
          <ErrorText>{error.message}</ErrorText>
        </div>
      )}
      <FormActions>
        <Button type="submit" disabled={submitting}>
          {submitting ? 'Saving…' : session ? 'Save session' : 'Create session'}
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
      </FormActions>
    </form>
  )
}
