import { useState, type FormEvent } from 'react'
import { listSessionAssets, type PhotoAsset } from '../api/assets.ts'
import {
  changeGalleryStatus,
  getGallery,
  GALLERY_DOWNLOADS,
  setGalleryArchived,
  setGalleryPhotos,
  updateGallery,
  type GalleryDetail,
  type GalleryDownloads,
} from '../api/galleries.ts'
import { paths } from '../routing.ts'
import { formatDate } from '../time.ts'
import { toFormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { Pager } from '../ui/Pager.tsx'
import { useLoad } from '../useLoad.ts'
import { downloadLabels, galleryStatusLabels } from './labels.ts'
import { ShareLinks } from './ShareLinks.tsx'

const PICKER_PAGE_SIZE = 100

type Props = { workspaceId: string; galleryId: string; timezone: string }

/** One gallery: publication, settings, photos and their order, and share links. */
export function GalleryPage({ workspaceId, galleryId, timezone }: Props) {
  const detail = useLoad((signal) => getGallery(workspaceId, galleryId, signal), [workspaceId, galleryId])

  if (detail.state.kind === 'loading') return <p role="status">Loading gallery…</p>
  if (detail.state.kind === 'error') {
    return <LoadError error={detail.state.error} what="gallery" onRetry={detail.reload} />
  }
  return (
    <GalleryView workspaceId={workspaceId} timezone={timezone} detail={detail.state.value} onChange={detail.replace} />
  )
}

type ViewProps = {
  workspaceId: string
  timezone: string
  detail: GalleryDetail
  onChange: (detail: GalleryDetail) => void
}

function GalleryView({ workspaceId, timezone, detail, onChange }: ViewProps) {
  const { gallery } = detail
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const archived = gallery.archivedAt !== null
  const published = gallery.status === 'PUBLISHED'

  /** Runs one change at a time; every change returns the whole gallery, including its new version. */
  async function run(action: () => Promise<GalleryDetail>, description: string) {
    setBusy(true)
    setActionError(null)
    try {
      onChange(await action())
    } catch (e) {
      setActionError(toFormError(e, description).message ?? `Could not ${description}.`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="gallery-heading">
      <p>
        <a href={paths.galleries()}>← All galleries</a> · <a href={paths.session(gallery.sessionId)}>Session</a>
      </p>
      <div className="toolbar">
        <h3 id="gallery-heading">
          {gallery.title}{' '}
          <span className={`badge gallery-${gallery.status.toLowerCase()}`}>{galleryStatusLabels[gallery.status]}</span>{' '}
          {archived && <span className="badge">Archived</span>}
        </h3>
        <span>
          {!archived && (
            <button
              type="button"
              disabled={busy}
              onClick={() =>
                run(
                  () => changeGalleryStatus(workspaceId, gallery.id, published ? 'DRAFT' : 'PUBLISHED'),
                  published ? 'unpublish the gallery' : 'publish the gallery',
                )
              }
            >
              {published ? 'Unpublish' : 'Publish'}
            </button>
          )}{' '}
          <button
            type="button"
            disabled={busy}
            onClick={() =>
              run(
                () => setGalleryArchived(workspaceId, gallery.id, !archived),
                archived ? 'restore the gallery' : 'archive the gallery',
              )
            }
          >
            {archived ? 'Restore' : 'Archive'}
          </button>
        </span>
      </div>
      {actionError && <p role="alert">{actionError}</p>}
      <p className="muted">
        {archived
          ? 'Archived galleries are closed to visitors. Restore it to change it or share it again.'
          : published
            ? 'Anyone with an active link can view this gallery.'
            : 'Draft: links do not open this gallery until you publish it.'}
      </p>
      {!archived && (
        <GallerySettings
          key={gallery.version}
          detail={detail}
          busy={busy}
          onSave={(input) => run(() => updateGallery(workspaceId, gallery.id, gallery.version, input), 'save the settings')}
        />
      )}
      {archived && <p>Downloads: {downloadLabels[gallery.downloads]}</p>}
      <GalleryPhotos
        workspaceId={workspaceId}
        detail={detail}
        editable={!archived}
        busy={busy}
        onSave={(assetIds) =>
          run(() => setGalleryPhotos(workspaceId, gallery.id, gallery.version, assetIds), 'save the photos')
        }
      />
      <ShareLinks workspaceId={workspaceId} timezone={timezone} gallery={gallery} />
      <p className="muted">Created {formatDate(gallery.createdAt, timezone)}</p>
    </section>
  )
}

function GallerySettings({
  detail,
  busy,
  onSave,
}: {
  detail: GalleryDetail
  busy: boolean
  onSave: (input: { title: string; downloads: GalleryDownloads }) => void
}) {
  const [title, setTitle] = useState(detail.gallery.title)
  const [downloads, setDownloads] = useState(detail.gallery.downloads)
  const changed = title !== detail.gallery.title || downloads !== detail.gallery.downloads

  function submit(event: FormEvent) {
    event.preventDefault()
    onSave({ title, downloads })
  }

  return (
    <form className="toolbar filters" onSubmit={submit} aria-label="Gallery settings">
      <label>
        Title <input value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
      </label>
      <label>
        Downloads{' '}
        <select value={downloads} onChange={(e) => setDownloads(e.target.value as GalleryDownloads)}>
          {GALLERY_DOWNLOADS.map((d) => (
            <option key={d} value={d}>
              {downloadLabels[d]}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={busy || !changed}>
        Save settings
      </button>
    </form>
  )
}

type PhotosProps = {
  workspaceId: string
  detail: GalleryDetail
  editable: boolean
  busy: boolean
  onSave: (assetIds: string[]) => void
}

/** The gallery's photos in order, with controls to reorder and remove them, and a picker for the session's photos. */
function GalleryPhotos({ workspaceId, detail, editable, busy, onSave }: PhotosProps) {
  const { gallery, photos } = detail
  const ids = photos.map((photo) => photo.id)

  function move(index: number, delta: number) {
    const next = [...ids]
    const [moved] = next.splice(index, 1)
    next.splice(index + delta, 0, moved)
    onSave(next)
  }

  return (
    <section aria-labelledby="gallery-photos-heading">
      <h4 id="gallery-photos-heading">
        Photos in this gallery ({photos.length})
      </h4>
      {photos.length === 0 ? (
        <p className="muted">No photos yet. Add some from the session below, then publish the gallery.</p>
      ) : (
        <ol className="photo-grid" aria-label="Gallery photos">
          {photos.map((photo, index) => (
            <li key={photo.id}>
              <figure>
                {photo.thumbnailUrl && <img src={photo.thumbnailUrl} alt={photo.originalFilename} loading="lazy" />}
                <figcaption>
                  {index + 1}. {photo.originalFilename}
                </figcaption>
              </figure>
              {editable && (
                <span className="photo-actions">
                  <button
                    type="button"
                    disabled={busy || index === 0}
                    onClick={() => move(index, -1)}
                    aria-label={`Move ${photo.originalFilename} earlier`}
                  >
                    ←
                  </button>
                  <button
                    type="button"
                    disabled={busy || index === photos.length - 1}
                    onClick={() => move(index, 1)}
                    aria-label={`Move ${photo.originalFilename} later`}
                  >
                    →
                  </button>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => onSave(ids.filter((id) => id !== photo.id))}
                    aria-label={`Remove ${photo.originalFilename}`}
                  >
                    Remove
                  </button>
                </span>
              )}
            </li>
          ))}
        </ol>
      )}
      {editable && (
        <SessionPhotoPicker
          workspaceId={workspaceId}
          sessionId={gallery.sessionId}
          inGallery={ids}
          busy={busy}
          onAdd={(added) => onSave([...ids, ...added])}
        />
      )}
    </section>
  )
}

type PickerProps = {
  workspaceId: string
  sessionId: string
  inGallery: string[]
  busy: boolean
  onAdd: (assetIds: string[]) => void
}

/** The session's ready photos that are not in the gallery yet. */
function SessionPhotoPicker({ workspaceId, sessionId, inGallery, busy, onAdd }: PickerProps) {
  const [pageNumber, setPageNumber] = useState(0)
  const assets = useLoad(
    (signal) => listSessionAssets(workspaceId, sessionId, { page: pageNumber, size: PICKER_PAGE_SIZE }, signal),
    [workspaceId, sessionId, pageNumber],
  )

  let content
  if (assets.state.kind === 'loading') content = <p role="status">Loading the session's photos…</p>
  else if (assets.state.kind === 'error') {
    content = <LoadError error={assets.state.error} what="session's photos" onRetry={assets.reload} />
  } else {
    const page = assets.state.value
    const available = page.items.filter((photo) => photo.status === 'READY' && !inGallery.includes(photo.id))
    content = (
      <>
        {available.length === 0 ? (
          <p className="muted">Every ready photo on this page of the session is in the gallery.</p>
        ) : (
          <>
            <button type="button" disabled={busy} onClick={() => onAdd(available.map((photo) => photo.id))}>
              Add all {available.length}
            </button>
            <ul className="photo-grid" aria-label="Session photos to add">
              {available.map((photo) => (
                <li key={photo.id}>
                  <PickerTile photo={photo} />
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => onAdd([photo.id])}
                    aria-label={`Add ${photo.originalFilename}`}
                  >
                    Add
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
        <Pager page={page} onPage={setPageNumber} />
      </>
    )
  }

  return (
    <section aria-labelledby="picker-heading">
      <h4 id="picker-heading">Add photos from the session</h4>
      {content}
    </section>
  )
}

function PickerTile({ photo }: { photo: PhotoAsset }) {
  return (
    <figure>
      {photo.thumbnailUrl && <img src={photo.thumbnailUrl} alt={photo.originalFilename} loading="lazy" />}
      <figcaption>{photo.originalFilename}</figcaption>
    </figure>
  )
}
