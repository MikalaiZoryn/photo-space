import { useState } from 'react'
import { listGalleries, type GalleryQuery } from '../api/galleries.ts'
import { paths } from '../routing.ts'
import { formatDate } from '../time.ts'
import { Images } from 'lucide-react'
import { EmptyState, Loading } from '../ui/Feedback.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { Pager } from '../ui/Pager.tsx'
import { RecordList, RecordRow } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { useLoad } from '../useLoad.ts'
import { galleryStatusLabels, galleryStatusTones } from './labels.ts'

type Props = {
  workspaceId: string
  timezone: string
  filter: Omit<GalleryQuery, 'page' | 'size'>
  emptyText: string
}

/** A paged list of galleries, newest first. */
export function GalleryList({ workspaceId, timezone, filter, emptyText }: Props) {
  const [pageNumber, setPageNumber] = useState(0)
  const galleries = useLoad(
    (signal) => listGalleries(workspaceId, { ...filter, page: pageNumber }, signal),
    [workspaceId, filter.sessionId, filter.archived, pageNumber],
  )

  if (galleries.state.kind === 'loading') return <Loading>Loading galleries…</Loading>
  if (galleries.state.kind === 'error') {
    return <LoadError error={galleries.state.error} what="galleries" onRetry={galleries.reload} />
  }
  const page = galleries.state.value
  if (page.totalItems === 0) return <EmptyState>{emptyText}</EmptyState>
  return (
    <>
      <RecordList label="Galleries">
        {page.items.map((gallery) => (
          <RecordRow
            key={gallery.id}
            href={paths.gallery(gallery.id)}
            leading={
              <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                <Images className="size-4.5" />
              </span>
            }
            title={gallery.title}
            meta={`${gallery.photoCount} ${gallery.photoCount === 1 ? 'photo' : 'photos'} · created ${formatDate(gallery.createdAt, timezone)}`}
            trailing={
              <>
                <StatusBadge tone={galleryStatusTones[gallery.status]}>{galleryStatusLabels[gallery.status]}</StatusBadge>
                {gallery.archivedAt && <StatusBadge>Archived</StatusBadge>}
              </>
            }
          />
        ))}
      </RecordList>
      <Pager page={page} onPage={setPageNumber} />
    </>
  )
}
