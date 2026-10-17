import { useState } from 'react'
import { listSessions, type SessionQuery } from '../api/sessions.ts'
import { paths } from '../routing.ts'
import { formatSessionTime } from '../time.ts'
import { EmptyState, Loading } from '../ui/Feedback.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { Pager } from '../ui/Pager.tsx'
import { RecordList, RecordRow } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { useLoad } from '../useLoad.ts'
import { statusLabels, statusTones } from './status.ts'

type Props = {
  workspaceId: string
  /** Fixed for the life of the component; give it a `key` that changes with the filter to start at page one. */
  filter: Omit<SessionQuery, 'page' | 'size'>
  emptyText: string
}

/** A paged list of sessions, newest first with unscheduled ones on top. */
export function SessionList({ workspaceId, filter, emptyText }: Props) {
  const [page, setPage] = useState(0)
  const { status, clientId, archived } = filter
  const sessions = useLoad(
    (signal) => listSessions(workspaceId, { status, clientId, archived, page }, signal),
    [workspaceId, status, clientId, archived, page],
  )

  if (sessions.state.kind === 'loading') return <Loading>Loading sessions…</Loading>
  if (sessions.state.kind === 'error') {
    return <LoadError error={sessions.state.error} what="sessions" onRetry={sessions.reload} />
  }
  const result = sessions.state.value
  if (result.totalItems === 0) return <EmptyState>{emptyText}</EmptyState>
  return (
    <>
      <RecordList>
        {result.items.map((session) => (
          <RecordRow
            key={session.id}
            href={paths.session(session.id)}
            title={session.title}
            meta={[formatSessionTime(session.startsAt, session.endsAt, session.timezone) ?? 'Not scheduled', session.client?.name]
              .filter(Boolean)
              .join(' · ')}
            trailing={
              <>
                <StatusBadge tone={statusTones[session.status]}>{statusLabels[session.status]}</StatusBadge>
                {session.archivedAt && <StatusBadge>Archived</StatusBadge>}
              </>
            }
          />
        ))}
      </RecordList>
      <Pager page={result} onPage={setPage} />
    </>
  )
}
