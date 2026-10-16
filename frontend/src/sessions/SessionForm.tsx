import { useState, type FormEvent } from 'react'
import type { ClientSummary } from '../api/clients.ts'
import { createSession, updateSession, type PhotoSession, type SessionInput } from '../api/sessions.ts'
import { toInputValue } from '../time.ts'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'
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
    <form onSubmit={submit} aria-label={session ? 'Edit session' : 'New session'} className="stack">
      <label>
        Title
        <input required maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} />
      </label>
      {error.fields.title && <p role="alert">Title {error.fields.title}</p>}
      <label>
        Session type
        <input list="session-types" maxLength={100} value={sessionType} onChange={(e) => setSessionType(e.target.value)} />
        <datalist id="session-types">
          {SESSION_TYPES.map((t) => (
            <option key={t} value={t} />
          ))}
        </datalist>
      </label>
      {error.fields.sessionType && <p role="alert">Session type {error.fields.sessionType}</p>}
      <ClientPicker workspaceId={workspaceId} value={client} onChange={setClient} />
      {error.fields.clientId && <p role="alert">Client {error.fields.clientId}</p>}
      <fieldset className="stack">
        <legend>When (times in {zone})</legend>
        <label>
          Start
          <input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} />
        </label>
        {error.fields.startLocal && <p role="alert">Start {error.fields.startLocal}</p>}
        <label>
          End
          <input type="datetime-local" value={end} min={start || undefined} onChange={(e) => setEnd(e.target.value)} />
        </label>
        {error.fields.endLocal && <p role="alert">End {error.fields.endLocal}</p>}
      </fieldset>
      <label>
        Location
        <input maxLength={300} value={location} onChange={(e) => setLocation(e.target.value)} />
      </label>
      {error.fields.location && <p role="alert">Location {error.fields.location}</p>}
      <label>
        Notes
        <textarea rows={4} maxLength={10000} value={notes} onChange={(e) => setNotes(e.target.value)} />
      </label>
      {error.fields.notes && <p role="alert">Notes {error.fields.notes}</p>}
      {error.message && <p role="alert">{error.message}</p>}
      <div>
        <button type="submit" disabled={submitting}>
          {submitting ? 'Saving…' : session ? 'Save session' : 'Create session'}
        </button>{' '}
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}
