import { useState } from 'react'
import { Plus } from 'lucide-react'
import { SESSION_STATUSES, type SessionStatus } from '../api/sessions.ts'
import { paths } from '../routing.ts'
import { Button } from '@/components/ui/button'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { CheckboxField } from '../ui/Field.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { FilterBar } from '../ui/RecordList.tsx'
import { SessionList } from './SessionList.tsx'
import { statusLabels } from './status.ts'

export function SessionsPage({ workspaceId, timezone }: { workspaceId: string; timezone: string }) {
  const [status, setStatus] = useState<SessionStatus | ''>('')
  const [archived, setArchived] = useState(false)
  const filtered = status !== '' || archived

  return (
    <section aria-labelledby="sessions-heading">
      <PageHeader
        id="sessions-heading"
        title="Sessions"
        description="Inquiries, bookings, and deliveries, newest first."
        actions={
          <Button asChild>
            <a href={paths.sessionNew()}>
              <Plus aria-hidden="true" />
              New session
            </a>
          </Button>
        }
      />
      <FilterBar>
        <label className="inline-flex items-center gap-2 text-sm">
          Status{' '}
          <NativeSelect className="bg-card" value={status} onChange={(e) => setStatus(e.target.value as SessionStatus | '')}>
            <NativeSelectOption value="">All statuses</NativeSelectOption>
            {SESSION_STATUSES.map((s) => (
              <NativeSelectOption key={s} value={s}>
                {statusLabels[s]}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </label>
        <CheckboxField label="Show archived" checked={archived} onChange={setArchived} />
        <span className="text-sm text-muted-foreground sm:ml-auto">Times in {timezone}</span>
      </FilterBar>
      <SessionList
        key={`${status}|${archived}`}
        workspaceId={workspaceId}
        filter={{ status, archived }}
        emptyText={filtered ? 'No sessions match these filters.' : 'No sessions yet. Create one to start planning.'}
      />
    </section>
  )
}
