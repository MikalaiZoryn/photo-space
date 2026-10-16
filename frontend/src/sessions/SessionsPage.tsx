import { useState } from 'react'
import { SESSION_STATUSES, type SessionStatus } from '../api/sessions.ts'
import { paths } from '../routing.ts'
import { SessionList } from './SessionList.tsx'
import { statusLabels } from './status.ts'

export function SessionsPage({ workspaceId, timezone }: { workspaceId: string; timezone: string }) {
  const [status, setStatus] = useState<SessionStatus | ''>('')
  const [archived, setArchived] = useState(false)
  const filtered = status !== '' || archived

  return (
    <section aria-labelledby="sessions-heading">
      <div className="toolbar">
        <h3 id="sessions-heading">Sessions</h3>
        <a className="button" href={paths.sessionNew()}>
          New session
        </a>
      </div>
      <div className="toolbar filters">
        <label>
          Status{' '}
          <select value={status} onChange={(e) => setStatus(e.target.value as SessionStatus | '')}>
            <option value="">All statuses</option>
            {SESSION_STATUSES.map((s) => (
              <option key={s} value={s}>
                {statusLabels[s]}
              </option>
            ))}
          </select>
        </label>
        <label>
          <input type="checkbox" checked={archived} onChange={(e) => setArchived(e.target.checked)} /> Show archived
        </label>
        <span className="muted">Times in {timezone}</span>
      </div>
      <SessionList
        key={`${status}|${archived}`}
        workspaceId={workspaceId}
        filter={{ status, archived }}
        emptyText={filtered ? 'No sessions match these filters.' : 'No sessions yet. Create one to start planning.'}
      />
    </section>
  )
}
