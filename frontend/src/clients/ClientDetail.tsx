import { useState } from 'react'
import { getClient, setClientArchived, type Client } from '../api/clients.ts'
import { navigate, paths } from '../routing.ts'
import { SessionList } from '../sessions/SessionList.tsx'
import { formatDate } from '../time.ts'
import { reference } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { useLoad } from '../useLoad.ts'
import { ClientForm } from './ClientForm.tsx'

type Props = { workspaceId: string; timezone: string; clientId: string }

export function ClientDetail({ workspaceId, timezone, clientId }: Props) {
  const client = useLoad((signal) => getClient(workspaceId, clientId, signal), [workspaceId, clientId])

  if (client.state.kind === 'loading') return <p role="status">Loading client…</p>
  if (client.state.kind === 'error') return <LoadError error={client.state.error} what="client" onRetry={client.reload} />
  return <ClientView workspaceId={workspaceId} timezone={timezone} client={client.state.value} onChange={client.replace} />
}

export function EditClient({ workspaceId, clientId }: { workspaceId: string; clientId: string }) {
  const client = useLoad((signal) => getClient(workspaceId, clientId, signal), [workspaceId, clientId])

  if (client.state.kind === 'loading') return <p role="status">Loading client…</p>
  if (client.state.kind === 'error') return <LoadError error={client.state.error} what="client" onRetry={client.reload} />
  if (client.state.value.archivedAt) {
    return (
      <p role="alert">
        This client is archived. Restore it before editing. <a href={paths.client(clientId)}>Back to client</a>
      </p>
    )
  }
  return (
    <section aria-labelledby="edit-client-heading">
      <h3 id="edit-client-heading">Edit client</h3>
      <ClientForm
        workspaceId={workspaceId}
        client={client.state.value}
        onSaved={(saved) => navigate(paths.client(saved.id))}
        onCancel={() => navigate(paths.client(clientId))}
      />
    </section>
  )
}

export function NewClient({ workspaceId }: { workspaceId: string }) {
  return (
    <section aria-labelledby="new-client-heading">
      <h3 id="new-client-heading">New client</h3>
      <ClientForm
        workspaceId={workspaceId}
        onSaved={(saved) => navigate(paths.client(saved.id))}
        onCancel={() => navigate(paths.clients())}
      />
    </section>
  )
}

function ClientView({
  workspaceId,
  timezone,
  client,
  onChange,
}: {
  workspaceId: string
  timezone: string
  client: Client
  onChange: (client: Client) => void
}) {
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const archived = client.archivedAt !== null

  async function toggleArchived() {
    setBusy(true)
    setActionError(null)
    try {
      onChange(await setClientArchived(workspaceId, client.id, !archived))
    } catch (e) {
      setActionError(`Could not ${archived ? 'restore' : 'archive'} the client${reference(e)}.`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="client-heading">
      <p>
        <a href={paths.clients()}>← All clients</a>
      </p>
      <div className="toolbar">
        <h3 id="client-heading">
          {client.name} {archived && <span className="badge">Archived</span>}
        </h3>
        <span>
          {!archived && (
            <a className="button" href={paths.clientEdit(client.id)}>
              Edit
            </a>
          )}{' '}
          <button type="button" disabled={busy} onClick={toggleArchived}>
            {archived ? 'Restore' : 'Archive'}
          </button>
        </span>
      </div>
      {actionError && <p role="alert">{actionError}</p>}
      {archived && client.archivedAt && (
        <p className="muted">
          Archived on {formatDate(client.archivedAt, timezone)}. Archived clients are hidden from lists and cannot be
          linked to new sessions.
        </p>
      )}
      <dl className="fields">
        <dt>Email</dt>
        <dd>{client.email ? <a href={`mailto:${client.email}`}>{client.email}</a> : '—'}</dd>
        <dt>Phone</dt>
        <dd>{client.phone ? <a href={`tel:${client.phone}`}>{client.phone}</a> : '—'}</dd>
        <dt>Notes</dt>
        <dd className="notes">{client.notes ?? '—'}</dd>
      </dl>
      <div className="toolbar">
        <h4>Sessions</h4>
        {!archived && (
          <a className="button" href={paths.sessionNew(client.id)}>
            New session for {client.name}
          </a>
        )}
      </div>
      <SessionList workspaceId={workspaceId} filter={{ clientId: client.id }} emptyText="No sessions with this client yet." />
    </section>
  )
}
