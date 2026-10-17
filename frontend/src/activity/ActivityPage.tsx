import { useState } from 'react'
import { listAuditEvents } from '../api/audit.ts'
import { formatSessionTime } from '../time.ts'
import { useLoad } from '../useLoad.ts'
import { EmptyState, Loading } from '../ui/Feedback.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Pager } from '../ui/Pager.tsx'
import { RecordList, RecordRow } from '../ui/RecordList.tsx'
import { actionLabels, targetHref } from './labels.ts'

/** Owners only: who changed what, newest first. */
export function ActivityPage({ workspaceId, timezone }: { workspaceId: string; timezone: string }) {
  const [page, setPage] = useState(0)
  const events = useLoad((signal) => listAuditEvents(workspaceId, { page }, signal), [workspaceId, page])

  return (
    <section aria-labelledby="activity-heading">
      <PageHeader
        id="activity-heading"
        title="Activity"
        description={`Sensitive changes and workflow outcomes, newest first. Times in ${timezone}.`}
      />
      {events.state.kind === 'loading' && <Loading>Loading activity…</Loading>}
      {events.state.kind === 'error' && (
        <LoadError error={events.state.error} what="activity" onRetry={events.reload} />
      )}
      {events.state.kind === 'loaded' && events.state.value.totalItems === 0 && <EmptyState>No activity yet.</EmptyState>}
      {events.state.kind === 'loaded' && events.state.value.totalItems > 0 && (
        <>
          <RecordList label="Activity">
            {events.state.value.items.map((event) => (
              <RecordRow
                key={event.id}
                href={targetHref(event) ?? undefined}
                title={actionLabels[event.action] ?? event.action}
                meta={[
                  event.actor?.displayName ?? 'Automation',
                  formatSessionTime(event.createdAt, null, timezone),
                  event.detail,
                ]
                  .filter(Boolean)
                  .join(' · ')}
              />
            ))}
          </RecordList>
          <Pager page={events.state.value} onPage={setPage} />
        </>
      )}
    </section>
  )
}
