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
import { Archive, ArchiveRestore, ArrowLeft, ArrowRight, EyeOff, Plus, Send, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { EmptyState, ErrorAlert, Loading, photoGrid } from '../ui/Feedback.tsx'
import { Field } from '../ui/Field.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Panel } from '../ui/Panel.tsx'
import { PhotoFigure, PhotoImage } from '../ui/Photo.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { downloadLabels, galleryStatusLabels, galleryStatusTones } from './labels.ts'
import { ShareLinks } from './ShareLinks.tsx'

const PICKER_PAGE_SIZE = 100

type Props = { workspaceId: string; galleryId: string; timezone: string }

/** One gallery: publication, settings, photos and their order, and share links. */
export function GalleryPage({ workspaceId, galleryId, timezone }: Props) {
  const detail = useLoad((signal) => getGallery(workspaceId, galleryId, signal), [workspaceId, galleryId])

  if (detail.state.kind === 'loading') return <Loading>Loading gallery…</Loading>
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
      <PageHeader
        id="gallery-heading"
        title={gallery.title}
        back={{ href: paths.galleries(), label: 'All galleries' }}
        badges={
          <>
            <StatusBadge tone={galleryStatusTones[gallery.status]}>{galleryStatusLabels[gallery.status]}</StatusBadge>
            {archived && <StatusBadge>Archived</StatusBadge>}
          </>
        }
        description={
          <>
            {archived
              ? 'Archived galleries are closed to visitors. Restore it to change it or share it again.'
              : published
                ? 'Anyone with an active link can view this gallery.'
                : 'Draft: links do not open this gallery until you publish it.'}{' '}
            <a href={paths.session(gallery.sessionId)} className="font-medium text-accent-foreground hover:underline">
              Session
            </a>
          </>
        }
        actions={
          <>
            <Button
              type="button"
              variant="outline"
              disabled={busy}
              onClick={() =>
                run(
                  () => setGalleryArchived(workspaceId, gallery.id, !archived),
                  archived ? 'restore the gallery' : 'archive the gallery',
                )
              }
            >
              {archived ? <ArchiveRestore aria-hidden="true" /> : <Archive aria-hidden="true" />}
              {archived ? 'Restore' : 'Archive'}
            </Button>
            {!archived && (
              <Button
                type="button"
                variant={published ? 'outline' : 'default'}
                disabled={busy}
                onClick={() =>
                  run(
                    () => changeGalleryStatus(workspaceId, gallery.id, published ? 'DRAFT' : 'PUBLISHED'),
                    published ? 'unpublish the gallery' : 'publish the gallery',
                  )
                }
              >
                {published ? <EyeOff aria-hidden="true" /> : <Send aria-hidden="true" />}
                {published ? 'Unpublish' : 'Publish'}
              </Button>
            )}
          </>
        }
      />
      <div className="space-y-6">
        {actionError && <ErrorAlert>{actionError}</ErrorAlert>}
        {!archived && (
          <GallerySettings
            key={gallery.version}
            detail={detail}
            busy={busy}
            onSave={(input) => run(() => updateGallery(workspaceId, gallery.id, gallery.version, input), 'save the settings')}
          />
        )}
        {archived && <p className="text-sm">Downloads: {downloadLabels[gallery.downloads]}</p>}
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
        <p className="text-sm text-muted-foreground">Created {formatDate(gallery.createdAt, timezone)}</p>
      </div>
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
    <form
      className="grid items-end gap-4 rounded-xl border bg-card p-4 shadow-xs sm:p-6 md:grid-cols-[1fr_1fr_auto]"
      onSubmit={submit}
      aria-label="Gallery settings"
    >
      <Field label="Title">
        <Input value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
      </Field>
      <Field label="Downloads">
        <NativeSelect className="w-full" value={downloads} onChange={(e) => setDownloads(e.target.value as GalleryDownloads)}>
          {GALLERY_DOWNLOADS.map((d) => (
            <NativeSelectOption key={d} value={d}>
              {downloadLabels[d]}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </Field>
      <Button type="submit" variant="secondary" disabled={busy || !changed}>
        Save settings
      </Button>
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
    <Panel id="gallery-photos-heading" title={`Photos in this gallery (${photos.length})`}>
      {photos.length === 0 ? (
        <EmptyState>No photos yet. Add some from the session below, then publish the gallery.</EmptyState>
      ) : (
        <ol className={photoGrid} aria-label="Gallery photos">
          {photos.map((photo, index) => (
            <li key={photo.id} className="space-y-2">
              <PhotoFigure caption={`${index + 1}. ${photo.originalFilename}`}>
                {photo.thumbnailUrl ? (
                  <PhotoImage src={photo.thumbnailUrl} alt={photo.originalFilename} />
                ) : (
                  <div className="aspect-square bg-muted" />
                )}
              </PhotoFigure>
              {editable && (
                <span className="flex gap-1">
                  <Button
                    type="button"
                    variant="outline"
                    size="icon-sm"
                    disabled={busy || index === 0}
                    onClick={() => move(index, -1)}
                    aria-label={`Move ${photo.originalFilename} earlier`}
                    title="Move earlier"
                  >
                    <ArrowLeft aria-hidden="true" />
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="icon-sm"
                    disabled={busy || index === photos.length - 1}
                    onClick={() => move(index, 1)}
                    aria-label={`Move ${photo.originalFilename} later`}
                    title="Move later"
                  >
                    <ArrowRight aria-hidden="true" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    className="ml-auto text-destructive hover:text-destructive"
                    disabled={busy}
                    onClick={() => onSave(ids.filter((id) => id !== photo.id))}
                    aria-label={`Remove ${photo.originalFilename}`}
                    title="Remove"
                  >
                    <Trash2 aria-hidden="true" />
                  </Button>
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
    </Panel>
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
  if (assets.state.kind === 'loading') content = <Loading>Loading the session's photos…</Loading>
  else if (assets.state.kind === 'error') {
    content = <LoadError error={assets.state.error} what="session's photos" onRetry={assets.reload} />
  } else {
    const page = assets.state.value
    const available = page.items.filter((photo) => photo.status === 'READY' && !inGallery.includes(photo.id))
    content = (
      <>
        {available.length === 0 ? (
          <p className="text-sm text-muted-foreground">Every ready photo on this page of the session is in the gallery.</p>
        ) : (
          <>
            <Button type="button" variant="secondary" size="sm" disabled={busy} onClick={() => onAdd(available.map((photo) => photo.id))}>
              <Plus aria-hidden="true" />
              Add all {available.length}
            </Button>
            <ul className={photoGrid} aria-label="Session photos to add">
              {available.map((photo) => (
                <li key={photo.id} className="space-y-2">
                  <PickerTile photo={photo} />
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="w-full"
                    disabled={busy}
                    onClick={() => onAdd([photo.id])}
                    aria-label={`Add ${photo.originalFilename}`}
                  >
                    <Plus aria-hidden="true" />
                    Add
                  </Button>
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
    <section aria-labelledby="picker-heading" className="space-y-4 border-t pt-6">
      <h5 id="picker-heading" className="text-sm font-semibold">
        Add photos from the session
      </h5>
      {content}
    </section>
  )
}

function PickerTile({ photo }: { photo: PhotoAsset }) {
  return (
    <PhotoFigure caption={photo.originalFilename}>
      {photo.thumbnailUrl ? <PhotoImage src={photo.thumbnailUrl} alt={photo.originalFilename} /> : <div className="aspect-square bg-muted" />}
    </PhotoFigure>
  )
}
