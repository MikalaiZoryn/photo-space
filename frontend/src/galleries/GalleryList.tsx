import { useState } from 'react'
import { listGalleries, type GalleryQuery } from '../api/galleries.ts'
import { paths } from '../routing.ts'
import { formatDate } from '../time.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { Pager } from '../ui/Pager.tsx'
import { useLoad } from '../useLoad.ts'
import { galleryStatusLabels } from './labels.ts'

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

  if (galleries.state.kind === 'loading') return <p role="status">Loading galleries…</p>
  if (galleries.state.kind === 'error') {
    return <LoadError error={galleries.state.error} what="galleries" onRetry={galleries.reload} />
  }
  const page = galleries.state.value
  if (page.totalItems === 0) return <p className="muted">{emptyText}</p>
  return (
    <>
      <ul className="records" aria-label="Galleries">
        {page.items.map((gallery) => (
          <li key={gallery.id}>
            <a href={paths.gallery(gallery.id)}>{gallery.title}</a>
            <span className={`badge gallery-${gallery.status.toLowerCase()}`}>{galleryStatusLabels[gallery.status]}</span>
            {gallery.archivedAt && <span className="badge">Archived</span>}
            <span className="muted">
              {gallery.photoCount} {gallery.photoCount === 1 ? 'photo' : 'photos'} · created{' '}
              {formatDate(gallery.createdAt, timezone)}
            </span>
          </li>
        ))}
      </ul>
      <Pager page={page} onPage={setPageNumber} />
    </>
  )
}
