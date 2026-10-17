import { useState } from 'react'
import { listAuditEvents, type AuditEvent } from '../api/audit.ts'
import { paths } from '../routing.ts'
import { formatSessionTime } from '../time.ts'
import { useLoad } from '../useLoad.ts'
import { EmptyState, Loading } from '../ui/Feedback.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Pager } from '../ui/Pager.tsx'
import { RecordList, RecordRow } from '../ui/RecordList.tsx'

const actionLabels: Record<string, string> = {
  SESSION_STATUS_CHANGED: 'Changed a session’s status',
  TASK_CREATED: 'Created a task',
  TASK_UPDATED: 'Edited a task',
  TASK_COMPLETED: 'Completed a task',
  TASK_REOPENED: 'Reopened a task',
  TASK_DELETED: 'Deleted a task',
  AUTOMATION_RULE_CREATED: 'Created an automation rule',
  AUTOMATION_RULE_UPDATED: 'Changed an automation rule',
  AUTOMATION_RUN_SUCCEEDED: 'An automation run created its task',
  AUTOMATION_RUN_FAILED: 'An automation run failed',
  AUTOMATION_RUN_RETRIED: 'Retried an automation run',
  GALLERY_PUBLISHED: 'Published a gallery',
  GALLERY_UNPUBLISHED: 'Unpublished a gallery',
  SHARE_LINK_CREATED: 'Created a gallery share link',
  SHARE_LINK_REVOKED: 'Revoked a gallery share link',
  CONTENT_DRAFT_APPROVED: 'Approved a content draft',
  CONTENT_DRAFT_UNAPPROVED: 'Withdrew a content draft approval',
}

/** Where an event's record can be opened, if it still has a page of its own. */
function targetHref(event: AuditEvent): string | null {
  switch (event.targetType) {
    case 'SESSION':
      return paths.session(event.targetId)
    case 'TASK':
      return event.action === 'TASK_DELETED' ? null : paths.taskEdit(event.targetId)
    case 'GALLERY':
      return paths.gallery(event.targetId)
    case 'AUTOMATION_RULE':
    case 'AUTOMATION_RUN':
      return paths.automation()
    default:
      return null
  }
}

/** Owners only: who changed what, newest first. */
export function ActivityPage({ workspaceId, timezone }: { workspaceId: string; timezone: string }) {
  const [page, setPage] = useState(0)
  const events = useLoad((signal) => listAuditEvents(workspaceId, page, signal), [workspaceId, page])

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
