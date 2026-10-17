import { useState } from 'react'
import { getClient, setClientArchived, type Client } from '../api/clients.ts'
import { navigate, paths } from '../routing.ts'
import { SessionList } from '../sessions/SessionList.tsx'
import { formatDate } from '../time.ts'
import { reference } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { useLoad } from '../useLoad.ts'
import { Archive, ArchiveRestore, Pencil, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { FieldItem, Fields } from '../ui/Fields.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Panel } from '../ui/Panel.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { ClientForm } from './ClientForm.tsx'

type Props = { workspaceId: string; timezone: string; clientId: string }

export function ClientDetail({ workspaceId, timezone, clientId }: Props) {
  const client = useLoad((signal) => getClient(workspaceId, clientId, signal), [workspaceId, clientId])

  if (client.state.kind === 'loading') return <Loading>Loading client…</Loading>
  if (client.state.kind === 'error') return <LoadError error={client.state.error} what="client" onRetry={client.reload} />
  return <ClientView workspaceId={workspaceId} timezone={timezone} client={client.state.value} onChange={client.replace} />
}

export function EditClient({ workspaceId, clientId }: { workspaceId: string; clientId: string }) {
  const client = useLoad((signal) => getClient(workspaceId, clientId, signal), [workspaceId, clientId])

  if (client.state.kind === 'loading') return <Loading>Loading client…</Loading>
  if (client.state.kind === 'error') return <LoadError error={client.state.error} what="client" onRetry={client.reload} />
  if (client.state.value.archivedAt) {
    return (
      <ErrorAlert>
        This client is archived. Restore it before editing. <a href={paths.client(clientId)}>Back to client</a>
      </ErrorAlert>
    )
  }
  return (
    <section aria-labelledby="edit-client-heading">
      <PageHeader id="edit-client-heading" title="Edit client" back={{ href: paths.client(clientId), label: 'Back to client' }} />
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
      <PageHeader id="new-client-heading" title="New client" back={{ href: paths.clients(), label: 'All clients' }} />
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
      <PageHeader
        id="client-heading"
        title={client.name}
        back={{ href: paths.clients(), label: 'All clients' }}
        badges={archived && <StatusBadge>Archived</StatusBadge>}
        actions={
          <>
            {!archived && (
              <Button asChild variant="outline">
                <a href={paths.clientEdit(client.id)}>
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
        {archived && client.archivedAt && (
          <p className="text-sm text-muted-foreground">
            Archived on {formatDate(client.archivedAt, timezone)}. Archived clients are hidden from lists and cannot be
            linked to new sessions.
          </p>
        )}
        <Fields>
          <FieldItem label="Email">{client.email ? <a href={`mailto:${client.email}`}>{client.email}</a> : '—'}</FieldItem>
          <FieldItem label="Phone">{client.phone ? <a href={`tel:${client.phone}`}>{client.phone}</a> : '—'}</FieldItem>
          <FieldItem label="Notes" wide>
            <span className="whitespace-pre-wrap">{client.notes ?? '—'}</span>
          </FieldItem>
        </Fields>
        <Panel
          id="client-sessions-heading"
          title="Sessions"
          actions={
            !archived && (
              <Button asChild size="sm">
                <a href={paths.sessionNew(client.id)}>
                  <Plus aria-hidden="true" />
                  New session for {client.name}
                </a>
              </Button>
            )
          }
        >
          <SessionList workspaceId={workspaceId} filter={{ clientId: client.id }} emptyText="No sessions with this client yet." />
        </Panel>
      </div>
    </section>
  )
}
