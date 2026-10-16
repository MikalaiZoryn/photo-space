import { useState } from 'react'
import { getClient, summaryOf } from '../api/clients.ts'
import {
  changeSessionStatus,
  getSession,
  setSessionArchived,
  type PhotoSession,
  type SessionStatus,
} from '../api/sessions.ts'
import { navigate, paths } from '../routing.ts'
import { formatDate, formatSessionTime } from '../time.ts'
import { toFormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { useLoad } from '../useLoad.ts'
import { SessionForm } from './SessionForm.tsx'
import { SessionPhotos } from './SessionPhotos.tsx'
import { statusActions, statusLabels } from './status.ts'

type Props = { workspaceId: string; sessionId: string }

export function SessionDetail({ workspaceId, sessionId }: Props) {
  const session = useLoad((signal) => getSession(workspaceId, sessionId, signal), [workspaceId, sessionId])

  if (session.state.kind === 'loading') return <p role="status">Loading session…</p>
  if (session.state.kind === 'error') {
    return <LoadError error={session.state.error} what="session" onRetry={session.reload} />
  }
  return <SessionView workspaceId={workspaceId} session={session.state.value} onChange={session.replace} />
}

export function EditSession({ workspaceId, timezone, sessionId }: Props & { timezone: string }) {
  const session = useLoad((signal) => getSession(workspaceId, sessionId, signal), [workspaceId, sessionId])

  if (session.state.kind === 'loading') return <p role="status">Loading session…</p>
  if (session.state.kind === 'error') {
    return <LoadError error={session.state.error} what="session" onRetry={session.reload} />
  }
  if (session.state.value.archivedAt) {
    return (
      <p role="alert">
        This session is archived. Restore it before editing. <a href={paths.session(sessionId)}>Back to session</a>
      </p>
    )
  }
  return (
    <section aria-labelledby="edit-session-heading">
      <h3 id="edit-session-heading">Edit session</h3>
      <SessionForm
        workspaceId={workspaceId}
        timezone={timezone}
        session={session.state.value}
        onSaved={(saved) => navigate(paths.session(saved.id))}
        onCancel={() => navigate(paths.session(sessionId))}
      />
    </section>
  )
}

/** A new session, optionally for the client given in the URL (from a client's page). */
export function NewSession({
  workspaceId,
  timezone,
  clientId,
}: {
  workspaceId: string
  timezone: string
  clientId: string | null
}) {
  const client = useLoad(
    (signal) => (clientId ? getClient(workspaceId, clientId, signal).then(summaryOf) : Promise.resolve(null)),
    [workspaceId, clientId],
  )

  if (client.state.kind === 'loading') return <p role="status">Loading…</p>
  if (client.state.kind === 'error') return <LoadError error={client.state.error} what="client" onRetry={client.reload} />
  return (
    <section aria-labelledby="new-session-heading">
      <h3 id="new-session-heading">New session</h3>
      <p className="muted">New sessions start as inquiries. Mark one booked once the date is confirmed.</p>
      <SessionForm
        workspaceId={workspaceId}
        timezone={timezone}
        initialClient={client.state.value}
        onSaved={(saved) => navigate(paths.session(saved.id))}
        onCancel={() => navigate(clientId ? paths.client(clientId) : paths.sessions())}
      />
    </section>
  )
}

function SessionView({
  workspaceId,
  session,
  onChange,
}: {
  workspaceId: string
  session: PhotoSession
  onChange: (session: PhotoSession) => void
}) {
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const archived = session.archivedAt !== null

  async function run(action: () => Promise<PhotoSession>, description: string) {
    setBusy(true)
    setActionError(null)
    try {
      onChange(await action())
    } catch (e) {
      setActionError(toFormError(e, description).message ?? `Could not ${description}.`)
    } finally {
      setBusy(false)
    }
  }

  const changeStatus = (status: SessionStatus) =>
    run(() => changeSessionStatus(workspaceId, session.id, status), 'change the status')
  const toggleArchived = () =>
    run(
      () => setSessionArchived(workspaceId, session.id, !archived),
      archived ? 'restore the session' : 'archive the session',
    )

  return (
    <section aria-labelledby="session-heading">
      <p>
        <a href={paths.sessions()}>← All sessions</a>
      </p>
      <div className="toolbar">
        <h3 id="session-heading">
          {session.title} {archived && <span className="badge">Archived</span>}
        </h3>
        <span>
          {!archived && (
            <a className="button" href={paths.sessionEdit(session.id)}>
              Edit
            </a>
          )}{' '}
          <button type="button" disabled={busy} onClick={toggleArchived}>
            {archived ? 'Restore' : 'Archive'}
          </button>
        </span>
      </div>
      {actionError && <p role="alert">{actionError}</p>}
      {archived && session.archivedAt && (
        <p className="muted">
          Archived on {formatDate(session.archivedAt, session.timezone)}. Restore it to edit it or change its status.
        </p>
      )}
      <div className="toolbar filters">
        <span>
          Status: <span className={`badge status-${session.status.toLowerCase()}`}>{statusLabels[session.status]}</span>
        </span>
        {session.allowedStatuses.length > 0 && (
          <span>
            {session.allowedStatuses.map((status) => (
              <button key={status} type="button" disabled={busy} onClick={() => changeStatus(status)}>
                {statusActions[status]}
              </button>
            ))}
          </span>
        )}
      </div>
      <dl className="fields">
        <dt>When</dt>
        <dd>
          {formatSessionTime(session.startsAt, session.endsAt, session.timezone) ?? 'Not scheduled'}
          {session.startsAt && <span className="muted"> ({session.timezone})</span>}
        </dd>
        <dt>Client</dt>
        <dd>
          {session.client ? (
            <>
              <a href={paths.client(session.client.id)}>{session.client.name}</a>
              {session.client.archived && <span className="badge">Archived</span>}
            </>
          ) : (
            '—'
          )}
        </dd>
        <dt>Type</dt>
        <dd>{session.sessionType ?? '—'}</dd>
        <dt>Location</dt>
        <dd>{session.location ?? '—'}</dd>
        <dt>Notes</dt>
        <dd className="notes">{session.notes ?? '—'}</dd>
      </dl>
      <SessionPhotos workspaceId={workspaceId} sessionId={session.id} archived={archived} />
    </section>
  )
}
