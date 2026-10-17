import { useEffect, useEffectEvent, useState } from 'react'
import {
  ACCEPTED_TYPES,
  listSessionAssets,
  MAX_UPLOAD_BYTES,
  StorageUploadError,
  uploadPhoto,
  type PhotoAsset,
} from '../api/assets.ts'
import { ApiError, type Page } from '../api/client.ts'
import { reference } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { Pager } from '../ui/Pager.tsx'
import { useLoad } from '../useLoad.ts'
import { ImageUp, X } from 'lucide-react'
import { cn } from 'cn'
import { Button, buttonVariants } from '@/components/ui/button'
import { EmptyState, Loading, photoGrid } from '../ui/Feedback.tsx'
import { Panel } from '../ui/Panel.tsx'
import { PhotoFigure, PhotoImage } from '../ui/Photo.tsx'
import { StatusBadge, type Tone } from '../ui/StatusBadge.tsx'

const PAGE_SIZE = 100
/** How often the list refreshes while photos are processing. */
export const POLL_MS = 2000
/** Files uploaded at the same time. */
const PARALLEL_UPLOADS = 3

type Upload = { key: number; name: string; state: 'waiting' | 'uploading' | 'failed'; message?: string }
type PhotosLoad = ReturnType<typeof useLoad<Page<PhotoAsset>>>

let nextUploadKey = 0

type Props = { workspaceId: string; sessionId: string; archived: boolean }

/** A session's photos: upload, processing status, and thumbnails. */
export function SessionPhotos({ workspaceId, sessionId, archived }: Props) {
  const [pageNumber, setPageNumber] = useState(0)
  const photos = useLoad(
    (signal) => listSessionAssets(workspaceId, sessionId, { page: pageNumber, size: PAGE_SIZE }, signal),
    [workspaceId, sessionId, pageNumber],
  )
  const [uploads, setUploads] = useState<Upload[]>([])
  // Bumped after each finished upload; refreshing from an effect uses the current page and load state.
  const [refreshRequests, setRefreshRequests] = useState(0)

  // Refreshes in place, without a loading state; a failed refresh leaves the list as it was.
  async function refresh() {
    try {
      photos.replace(await listSessionAssets(workspaceId, sessionId, { page: pageNumber, size: PAGE_SIZE }))
    } catch {
      // The next poll or upload tries again.
    }
  }
  const poll = useEffectEvent(() => void refresh())

  const processing =
    photos.state.kind === 'loaded' && photos.state.value.items.some((photo) => photo.status === 'PROCESSING')
  useEffect(() => {
    if (!processing) return
    const timer = setInterval(poll, POLL_MS)
    return () => clearInterval(timer)
  }, [processing])
  useEffect(() => {
    if (refreshRequests > 0) poll()
  }, [refreshRequests])

  function update(key: number, change: Partial<Upload> | null) {
    setUploads((current) =>
      change === null
        ? current.filter((upload) => upload.key !== key)
        : current.map((upload) => (upload.key === key ? { ...upload, ...change } : upload)),
    )
  }

  async function uploadAll(files: File[]) {
    const queue = files.map((file) => ({ file, key: nextUploadKey++ }))
    // Built now: the workers below empty `queue` before React runs the state updater.
    const added: Upload[] = queue.map(({ file, key }) => ({ key, name: file.name, state: 'waiting' }))
    setUploads((current) => [...current, ...added])
    const work = async () => {
      for (let next = queue.shift(); next; next = queue.shift()) {
        const problem = checkFile(next.file)
        if (problem) {
          update(next.key, { state: 'failed', message: problem })
          continue
        }
        update(next.key, { state: 'uploading' })
        try {
          await uploadPhoto(workspaceId, sessionId, next.file)
          update(next.key, null)
          setRefreshRequests((n) => n + 1)
        } catch (e) {
          update(next.key, { state: 'failed', message: uploadError(e) })
        }
      }
    }
    await Promise.all(Array.from({ length: Math.min(PARALLEL_UPLOADS, files.length) }, work))
  }

  return (
    <Panel
      id="photos-heading"
      title="Photos"
      actions={
        !archived && (
          <label className={cn(buttonVariants({ size: 'sm' }), 'focus-within:ring-3 focus-within:ring-ring/50')}>
            <ImageUp aria-hidden="true" />
            Upload photos
            <input
              className="sr-only"
              type="file"
              multiple
              accept={ACCEPTED_TYPES.join(',')}
              onChange={(event) => {
                const files = Array.from(event.target.files ?? [])
                event.target.value = ''
                if (files.length > 0) void uploadAll(files)
              }}
            />
          </label>
        )
      }
    >
      {archived && <p className="text-sm text-muted-foreground">Restore the session to upload photos.</p>}
      {uploads.length > 0 && (
        <ul aria-label="Uploads" className="divide-y rounded-lg border text-sm">
          {uploads.map((upload) => (
            <li key={upload.key} className="flex flex-wrap items-center gap-x-2 gap-y-1 px-3 py-2">
              <span className="min-w-0 truncate font-medium">{upload.name}:</span>{' '}
              {upload.state === 'failed' ? (
                <>
                  <span role="alert" className="text-destructive">
                    {upload.message}
                  </span>{' '}
                  <Button type="button" variant="ghost" size="sm" className="ml-auto" onClick={() => update(upload.key, null)}>
                    <X aria-hidden="true" />
                    Dismiss
                  </Button>
                </>
              ) : (
                <span className="text-muted-foreground">{upload.state === 'uploading' ? 'uploading…' : 'waiting…'}</span>
              )}
            </li>
          ))}
        </ul>
      )}
      <PhotoList photos={photos} onPage={setPageNumber} />
    </Panel>
  )
}

function PhotoList({ photos, onPage }: { photos: PhotosLoad; onPage: (page: number) => void }) {
  if (photos.state.kind === 'loading') return <Loading>Loading photos…</Loading>
  if (photos.state.kind === 'error') return <LoadError error={photos.state.error} what="photo list" onRetry={photos.reload} />
  const page = photos.state.value
  if (page.totalItems === 0) return <EmptyState>No photos yet. JPEG or PNG, up to 100 MB each.</EmptyState>
  return (
    <>
      <ul className={photoGrid} aria-label="Session photos">
        {page.items.map((photo) => (
          <li key={photo.id}>
            <PhotoTile photo={photo} />
          </li>
        ))}
      </ul>
      <Pager page={page} onPage={onPage} />
    </>
  )
}

const statusTones: Record<PhotoAsset['status'], Tone> = {
  PENDING_UPLOAD: 'neutral',
  PROCESSING: 'info',
  READY: 'success',
  REJECTED: 'danger',
  FAILED: 'danger',
}

function PhotoTile({ photo }: { photo: PhotoAsset }) {
  if (photo.status === 'READY' && photo.thumbnailUrl) {
    return (
      <PhotoFigure caption={photo.originalFilename}>
        <a href={photo.previewUrl ?? photo.thumbnailUrl} target="_blank" rel="noreferrer" className="block rounded-none">
          <PhotoImage src={photo.thumbnailUrl} alt={photo.originalFilename} />
        </a>
      </PhotoFigure>
    )
  }
  const label = {
    PENDING_UPLOAD: 'Waiting for upload',
    PROCESSING: 'Processing…',
    READY: 'Ready',
    REJECTED: 'Rejected',
    FAILED: 'Failed',
  }[photo.status]
  return (
    <PhotoFigure caption={photo.originalFilename}>
      <div className="flex aspect-square flex-col items-start justify-center gap-2 bg-muted/60 p-3 text-xs">
        <StatusBadge tone={statusTones[photo.status]}>{label}</StatusBadge>
        {photo.failureReason && <span className="text-tone-danger">{photo.failureReason}</span>}
      </div>
    </PhotoFigure>
  )
}

/** A quick check before uploading; the server enforces the same limits and verifies the content. */
function checkFile(file: File): string | null {
  if (!ACCEPTED_TYPES.includes(file.type)) return 'Only JPEG and PNG photos can be uploaded.'
  if (file.size === 0) return 'The file is empty.'
  if (file.size > MAX_UPLOAD_BYTES) return 'The file is larger than 100 MB.'
  return null
}

function uploadError(error: unknown): string {
  if (error instanceof ApiError && error.status === 400 && error.problem.errors) {
    return Object.values(error.problem.errors).join('; ')
  }
  if (error instanceof ApiError && error.status === 409 && error.problem.detail) return error.problem.detail
  if (error instanceof StorageUploadError) return 'The upload to storage failed. Try again.'
  return `The upload failed${reference(error)}. Try again.`
}
