import { useState } from 'react'
import { getClient, summaryOf } from '../api/clients.ts'
import { SessionGalleries } from '../galleries/SessionGalleries.tsx'
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
import { Archive, ArchiveRestore, Pencil } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { FieldItem, Fields } from '../ui/Fields.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { SessionForm } from './SessionForm.tsx'
import { SessionPhotos } from './SessionPhotos.tsx'
import { statusActions, statusLabels, statusTones } from './status.ts'

type Props = { workspaceId: string; sessionId: string }

export function SessionDetail({ workspaceId, sessionId }: Props) {
  const session = useLoad((signal) => getSession(workspaceId, sessionId, signal), [workspaceId, sessionId])

  if (session.state.kind === 'loading') return <Loading>Loading session…</Loading>
  if (session.state.kind === 'error') {
    return <LoadError error={session.state.error} what="session" onRetry={session.reload} />
  }
  return <SessionView workspaceId={workspaceId} session={session.state.value} onChange={session.replace} />
}

export function EditSession({ workspaceId, timezone, sessionId }: Props & { timezone: string }) {
  const session = useLoad((signal) => getSession(workspaceId, sessionId, signal), [workspaceId, sessionId])

  if (session.state.kind === 'loading') return <Loading>Loading session…</Loading>
  if (session.state.kind === 'error') {
    return <LoadError error={session.state.error} what="session" onRetry={session.reload} />
  }
  if (session.state.value.archivedAt) {
    return (
      <ErrorAlert>
        This session is archived. Restore it before editing. <a href={paths.session(sessionId)}>Back to session</a>
      </ErrorAlert>
    )
  }
  return (
    <section aria-labelledby="edit-session-heading">
      <PageHeader id="edit-session-heading" title="Edit session" back={{ href: paths.session(sessionId), label: 'Back to session' }} />
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

  if (client.state.kind === 'loading') return <Loading>Loading…</Loading>
  if (client.state.kind === 'error') return <LoadError error={client.state.error} what="client" onRetry={client.reload} />
  return (
    <section aria-labelledby="new-session-heading">
      <PageHeader
        id="new-session-heading"
        title="New session"
        description="New sessions start as inquiries. Mark one booked once the date is confirmed."
        back={clientId ? { href: paths.client(clientId), label: 'Back to client' } : { href: paths.sessions(), label: 'All sessions' }}
      />
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
      <PageHeader
        id="session-heading"
        title={session.title}
        back={{ href: paths.sessions(), label: 'All sessions' }}
        badges={
          <>
            <StatusBadge tone={statusTones[session.status]}>{statusLabels[session.status]}</StatusBadge>
            {archived && <StatusBadge>Archived</StatusBadge>}
          </>
        }
        actions={
          <>
            {!archived && (
              <Button asChild variant="outline">
                <a href={paths.sessionEdit(session.id)}>
                  <Pencil aria-hidden="true" />
                  Edit
                </a>
              </Button>
            )}
            <Button type="button" variant="outline" disabled={busy} onClick={toggleArchived}>
              {archived ? <ArchiveRestore aria-hidden="true" /> : <Archive aria-hidden="true" />}
              {archived ? 'Restore' : 'Archive'}
            </Button>
          </>
        }
      />
      <div className="space-y-6">
        {actionError && <ErrorAlert>{actionError}</ErrorAlert>}
        {archived && session.archivedAt && (
          <p className="text-sm text-muted-foreground">
            Archived on {formatDate(session.archivedAt, session.timezone)}. Restore it to edit it or change its status.
          </p>
        )}
        {session.allowedStatuses.length > 0 && (
          <div className="flex flex-wrap items-center gap-2 rounded-xl border bg-card p-3 shadow-xs sm:px-4">
            <span className="mr-auto text-sm font-medium text-muted-foreground">Next step</span>
            {session.allowedStatuses.map((status) => (
              <Button
                key={status}
                type="button"
                size="sm"
                variant={status === 'CANCELLED' ? 'destructive' : 'secondary'}
                disabled={busy}
                onClick={() => changeStatus(status)}
              >
                {statusActions[status]}
              </Button>
            ))}
          </div>
        )}
        <Fields>
          <FieldItem label="When">
            {formatSessionTime(session.startsAt, session.endsAt, session.timezone) ?? 'Not scheduled'}
            {session.startsAt && <span className="text-muted-foreground"> ({session.timezone})</span>}
          </FieldItem>
          <FieldItem label="Client">
            {session.client ? (
              <span className="inline-flex flex-wrap items-center gap-2">
                <a href={paths.client(session.client.id)}>{session.client.name}</a>
                {session.client.archived && <StatusBadge>Archived</StatusBadge>}
              </span>
            ) : (
              '—'
            )}
          </FieldItem>
          <FieldItem label="Type">{session.sessionType ?? '—'}</FieldItem>
          <FieldItem label="Location">{session.location ?? '—'}</FieldItem>
          <FieldItem label="Notes" wide>
            <span className="whitespace-pre-wrap">{session.notes ?? '—'}</span>
          </FieldItem>
        </Fields>
        <SessionPhotos workspaceId={workspaceId} sessionId={session.id} archived={archived} />
        <SessionGalleries
          workspaceId={workspaceId}
          sessionId={session.id}
          timezone={session.timezone}
          archived={archived}
        />
      </div>
    </section>
  )
}
