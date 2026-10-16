import { useState } from 'react'
import { listClients } from '../api/clients.ts'
import { paths } from '../routing.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { Pager } from '../ui/Pager.tsx'
import { useLoad } from '../useLoad.ts'

export function ClientsPage({ workspaceId }: { workspaceId: string }) {
  const [search, setSearch] = useState('')
  const [archived, setArchived] = useState(false)
  const [page, setPage] = useState(0)
  const query = search.trim()
  const clients = useLoad(
    (signal) => listClients(workspaceId, { q: query, archived, page }, signal),
    [workspaceId, query, archived, page],
  )

  return (
    <section aria-labelledby="clients-heading">
      <div className="toolbar">
        <h3 id="clients-heading">Clients</h3>
        <a className="button" href={paths.clientNew()}>
          New client
        </a>
      </div>
      <div className="toolbar filters">
        <label>
          Search{' '}
          <input
            type="search"
            maxLength={100}
            placeholder="Name or email"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value)
              setPage(0)
            }}
          />
        </label>
        <label>
          <input
            type="checkbox"
            checked={archived}
            onChange={(e) => {
              setArchived(e.target.checked)
              setPage(0)
            }}
          />{' '}
          Show archived
        </label>
      </div>
      {clients.state.kind === 'loading' && <p role="status">Loading clients…</p>}
      {clients.state.kind === 'error' && <LoadError error={clients.state.error} what="clients" onRetry={clients.reload} />}
      {clients.state.kind === 'loaded' && clients.state.value.totalItems === 0 && (
        <p>
          {query
            ? 'No clients match your search.'
            : archived
              ? 'No archived clients.'
              : 'No clients yet. Add your first client to link them to sessions.'}
        </p>
      )}
      {clients.state.kind === 'loaded' && clients.state.value.items.length > 0 && (
        <ul className="records">
          {clients.state.value.items.map((client) => (
            <li key={client.id}>
              <a href={paths.client(client.id)}>{client.name}</a>
              {client.archivedAt && <span className="badge">Archived</span>}
              <span className="muted">{[client.email, client.phone].filter(Boolean).join(' · ')}</span>
            </li>
          ))}
        </ul>
      )}
      {clients.state.kind === 'loaded' && <Pager page={clients.state.value} onPage={setPage} />}
    </section>
  )
}
