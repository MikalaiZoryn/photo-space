import { useState } from 'react'
import { listClients, summaryOf, type ClientSummary } from '../api/clients.ts'
import { useLoad } from '../useLoad.ts'

type Props = {
  workspaceId: string
  value: ClientSummary | null
  onChange: (client: ClientSummary | null) => void
}

const RESULTS = 20

/**
 * Picks an active client by searching the server, so it works however many clients there are. The current
 * client stays selectable even when it is archived or outside the search results.
 */
export function ClientPicker({ workspaceId, value, onChange }: Props) {
  const [search, setSearch] = useState('')
  const query = search.trim()
  const results = useLoad(
    (signal) => listClients(workspaceId, { q: query, size: RESULTS }, signal),
    [workspaceId, query],
  )
  const options = results.state.kind === 'loaded' ? results.state.value.items.map(summaryOf) : []
  const showCurrent = value !== null && !options.some((c) => c.id === value.id)

  function select(id: string) {
    onChange(id === '' ? null : (options.find((c) => c.id === id) ?? value))
  }

  return (
    <fieldset className="stack">
      <legend>Client</legend>
      <label>
        Find a client
        <input
          type="search"
          maxLength={100}
          placeholder="Name or email"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </label>
      <label>
        Linked client
        <select value={value?.id ?? ''} onChange={(e) => select(e.target.value)}>
          <option value="">No client</option>
          {showCurrent && (
            <option value={value.id}>
              {value.name}
              {value.archived ? ' (archived)' : ''}
            </option>
          )}
          {options.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </label>
      {results.state.kind === 'loading' && <span role="status">Searching clients…</span>}
      {results.state.kind === 'error' && <span role="alert">Could not load clients.</span>}
      {results.state.kind === 'loaded' && results.state.value.totalItems > RESULTS && (
        <span className="muted">Showing the first {RESULTS} matches. Type to narrow the list.</span>
      )}
      {results.state.kind === 'loaded' && results.state.value.totalItems === 0 && (
        <span className="muted">{query ? 'No active clients match.' : 'No clients yet.'}</span>
      )}
    </fieldset>
  )
}
