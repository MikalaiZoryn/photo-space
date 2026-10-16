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
    <section aria-labelledby="photos-heading">
      <div className="toolbar">
        <h4 id="photos-heading">Photos</h4>
        {!archived && (
          <label className="button">
            Upload photos
            <input
              className="visually-hidden"
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
        )}
      </div>
      {archived && <p className="muted">Restore the session to upload photos.</p>}
      {uploads.length > 0 && (
        <ul aria-label="Uploads" className="uploads">
          {uploads.map((upload) => (
            <li key={upload.key}>
              {upload.name}:{' '}
              {upload.state === 'failed' ? (
                <>
                  <span role="alert">{upload.message}</span>{' '}
                  <button type="button" onClick={() => update(upload.key, null)}>
                    Dismiss
                  </button>
                </>
              ) : upload.state === 'uploading' ? (
                'uploading…'
              ) : (
                'waiting…'
              )}
            </li>
          ))}
        </ul>
      )}
      <PhotoList photos={photos} onPage={setPageNumber} />
    </section>
  )
}

function PhotoList({ photos, onPage }: { photos: PhotosLoad; onPage: (page: number) => void }) {
  if (photos.state.kind === 'loading') return <p role="status">Loading photos…</p>
  if (photos.state.kind === 'error') return <LoadError error={photos.state.error} what="photo list" onRetry={photos.reload} />
  const page = photos.state.value
  if (page.totalItems === 0) return <p className="muted">No photos yet. JPEG or PNG, up to 100 MB each.</p>
  return (
    <>
      <ul className="photo-grid" aria-label="Session photos">
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

function PhotoTile({ photo }: { photo: PhotoAsset }) {
  if (photo.status === 'READY' && photo.thumbnailUrl) {
    return (
      <figure>
        <a href={photo.previewUrl ?? photo.thumbnailUrl} target="_blank" rel="noreferrer">
          <img src={photo.thumbnailUrl} alt={photo.originalFilename} loading="lazy" />
        </a>
        <figcaption>{photo.originalFilename}</figcaption>
      </figure>
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
    <figure className="photo-placeholder">
      <span className={`badge photo-${photo.status.toLowerCase()}`}>{label}</span>
      {photo.failureReason && <span>{photo.failureReason}</span>}
      <figcaption>{photo.originalFilename}</figcaption>
    </figure>
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
