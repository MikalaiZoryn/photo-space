import { useState } from 'react'
import { listClients, summaryOf, type ClientSummary } from '../api/clients.ts'
import { useLoad } from '../useLoad.ts'
import { Input } from '@/components/ui/input'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { Field } from '../ui/Field.tsx'

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
    <fieldset className="grid gap-4 md:grid-cols-2">
      <legend className="mb-2 text-sm font-semibold">Client</legend>
      <Field label="Find a client">
        <Input
          type="search"
          maxLength={100}
          placeholder="Name or email"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </Field>
      <Field label="Linked client">
        <NativeSelect className="w-full" value={value?.id ?? ''} onChange={(e) => select(e.target.value)}>
          <NativeSelectOption value="">No client</NativeSelectOption>
          {showCurrent && (
            <NativeSelectOption value={value.id}>
              {value.name}
              {value.archived ? ' (archived)' : ''}
            </NativeSelectOption>
          )}
          {options.map((c) => (
            <NativeSelectOption key={c.id} value={c.id}>
              {c.name}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </Field>
      <div className="text-sm text-muted-foreground md:col-span-2 empty:hidden">
        {results.state.kind === 'loading' && <span role="status">Searching clients…</span>}
        {results.state.kind === 'error' && (
          <span role="alert" className="text-destructive">
            Could not load clients.
          </span>
        )}
        {results.state.kind === 'loaded' && results.state.value.totalItems > RESULTS && (
          <span>Showing the first {RESULTS} matches. Type to narrow the list.</span>
        )}
        {results.state.kind === 'loaded' && results.state.value.totalItems === 0 && (
          <span>{query ? 'No active clients match.' : 'No clients yet.'}</span>
        )}
      </div>
    </fieldset>
  )
}
