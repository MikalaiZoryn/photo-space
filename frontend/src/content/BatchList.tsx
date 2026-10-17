import { useState } from 'react'
import { listBatches } from '../api/content.ts'
import { paths } from '../routing.ts'
import { formatDate } from '../time.ts'
import { Sparkles } from 'lucide-react'
import { EmptyState, Loading } from '../ui/Feedback.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { Pager } from '../ui/Pager.tsx'
import { RecordList, RecordRow } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { useLoad } from '../useLoad.ts'
import { batchStatusLabels, batchStatusTones } from './labels.ts'

type Props = {
  workspaceId: string
  timezone: string
  /** Only this session's batches; all of them otherwise, titled by session. */
  sessionId?: string
  emptyText: string
}

/** A paged list of generation batches, newest first. */
export function BatchList({ workspaceId, timezone, sessionId, emptyText }: Props) {
  const [pageNumber, setPageNumber] = useState(0)
  const batches = useLoad(
    (signal) => listBatches(workspaceId, { sessionId, page: pageNumber }, signal),
    [workspaceId, sessionId, pageNumber],
  )

  if (batches.state.kind === 'loading') return <Loading>Loading drafts…</Loading>
  if (batches.state.kind === 'error') {
    return <LoadError error={batches.state.error} what="drafts" onRetry={batches.reload} />
  }
  const page = batches.state.value
  if (page.totalItems === 0) return <EmptyState>{emptyText}</EmptyState>
  return (
    <>
      <RecordList label="Content batches">
        {page.items.map((batch) => (
          <RecordRow
            key={batch.id}
            href={paths.contentBatch(batch.id)}
            leading={
              <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                <Sparkles className="size-4.5" />
              </span>
            }
            title={sessionId ? `${batch.captionCount} caption ${batch.captionCount === 1 ? 'option' : 'options'}` : batch.sessionTitle}
            meta={[
              `${batch.photoCount} ${batch.photoCount === 1 ? 'photo' : 'photos'}`,
              batch.status === 'SUCCEEDED' && `${batch.draftCount} drafts, ${batch.approvedCount} approved`,
              `created ${formatDate(batch.createdAt, timezone)}`,
            ]
              .filter(Boolean)
              .join(' · ')}
            trailing={<StatusBadge tone={batchStatusTones[batch.status]}>{batchStatusLabels[batch.status]}</StatusBadge>}
          />
        ))}
      </RecordList>
      <Pager page={page} onPage={setPageNumber} />
    </>
  )
}
