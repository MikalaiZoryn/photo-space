import { useState } from 'react'
import { listSessions, type SessionQuery } from '../api/sessions.ts'
import { paths } from '../routing.ts'
import { formatSessionTime } from '../time.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { Pager } from '../ui/Pager.tsx'
import { useLoad } from '../useLoad.ts'
import { statusLabels } from './status.ts'

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

  if (sessions.state.kind === 'loading') return <p role="status">Loading sessions…</p>
  if (sessions.state.kind === 'error') {
    return <LoadError error={sessions.state.error} what="sessions" onRetry={sessions.reload} />
  }
  const result = sessions.state.value
  if (result.totalItems === 0) return <p>{emptyText}</p>
  return (
    <>
      <ul className="records">
        {result.items.map((session) => (
          <li key={session.id}>
            <a href={paths.session(session.id)}>{session.title}</a>
            <span className={`badge status-${session.status.toLowerCase()}`}>{statusLabels[session.status]}</span>
            {session.archivedAt && <span className="badge">Archived</span>}
            <span className="muted">
              {[formatSessionTime(session.startsAt, session.endsAt, session.timezone) ?? 'Not scheduled', session.client?.name]
                .filter(Boolean)
                .join(' · ')}
            </span>
          </li>
        ))}
      </ul>
      <Pager page={result} onPage={setPage} />
    </>
  )
}
