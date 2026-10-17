import { useState } from 'react'
import { Plus, Search } from 'lucide-react'
import { listClients } from '../api/clients.ts'
import { paths } from '../routing.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { EmptyState, Loading } from '../ui/Feedback.tsx'
import { CheckboxField } from '../ui/Field.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Pager } from '../ui/Pager.tsx'
import { FilterBar, Initials, RecordList, RecordRow } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
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
      <PageHeader
        id="clients-heading"
        title="Clients"
        description="Everyone you photograph, with their contact details and sessions."
        actions={
          <Button asChild>
            <a href={paths.clientNew()}>
              <Plus aria-hidden="true" />
              New client
            </a>
          </Button>
        }
      />
      <FilterBar>
        <label className="relative w-full sm:w-72">
          <span className="sr-only">Search</span>
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
          <Input
            type="search"
            className="bg-card pl-9"
            maxLength={100}
            placeholder="Name or email"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value)
              setPage(0)
            }}
          />
        </label>
        <CheckboxField
          label="Show archived"
          checked={archived}
          onChange={(checked) => {
            setArchived(checked)
            setPage(0)
          }}
        />
      </FilterBar>
      {clients.state.kind === 'loading' && <Loading>Loading clients…</Loading>}
      {clients.state.kind === 'error' && <LoadError error={clients.state.error} what="clients" onRetry={clients.reload} />}
      {clients.state.kind === 'loaded' && clients.state.value.totalItems === 0 && (
        <EmptyState>
          {query
            ? 'No clients match your search.'
            : archived
              ? 'No archived clients.'
              : 'No clients yet. Add your first client to link them to sessions.'}
        </EmptyState>
      )}
      {clients.state.kind === 'loaded' && clients.state.value.items.length > 0 && (
        <RecordList>
          {clients.state.value.items.map((client) => (
            <RecordRow
              key={client.id}
              href={paths.client(client.id)}
              leading={<Initials name={client.name} />}
              title={client.name}
              meta={[client.email, client.phone].filter(Boolean).join(' · ') || undefined}
              trailing={client.archivedAt && <StatusBadge>Archived</StatusBadge>}
            />
          ))}
        </RecordList>
      )}
      {clients.state.kind === 'loaded' && <Pager page={clients.state.value} onPage={setPage} />}
    </section>
  )
}
