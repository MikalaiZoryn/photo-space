import { useState, type FormEvent } from 'react'
import { createGallery, GALLERY_DOWNLOADS, type GalleryDownloads } from '../api/galleries.ts'
import { navigate, paths } from '../routing.ts'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'
import { GalleryList } from './GalleryList.tsx'
import { downloadLabels } from './labels.ts'

type Props = { workspaceId: string; sessionId: string; timezone: string; archived: boolean }

/** A session's galleries, and a form to start a new one from its photos. */
export function SessionGalleries({ workspaceId, sessionId, timezone, archived }: Props) {
  const [creating, setCreating] = useState(false)

  return (
    <section aria-labelledby="session-galleries-heading">
      <div className="toolbar">
        <h4 id="session-galleries-heading">Galleries</h4>
        {!archived && !creating && (
          <button type="button" onClick={() => setCreating(true)}>
            New gallery
          </button>
        )}
      </div>
      {creating && <NewGalleryForm workspaceId={workspaceId} sessionId={sessionId} onCancel={() => setCreating(false)} />}
      <GalleryList
        workspaceId={workspaceId}
        timezone={timezone}
        filter={{ sessionId }}
        emptyText="No galleries for this session yet."
      />
    </section>
  )
}

function NewGalleryForm({
  workspaceId,
  sessionId,
  onCancel,
}: {
  workspaceId: string
  sessionId: string
  onCancel: () => void
}) {
  const [title, setTitle] = useState('')
  const [downloads, setDownloads] = useState<GalleryDownloads>('NONE')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    setError(noFormError)
    try {
      const created = await createGallery(workspaceId, sessionId, { title, downloads })
      navigate(paths.gallery(created.gallery.id))
    } catch (e) {
      setError(toFormError(e, 'create the gallery'))
      setSaving(false)
    }
  }

  return (
    <form className="stack" onSubmit={submit} aria-label="New gallery">
      <label>
        Title
        <input value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
        {error.fields.title && <span role="alert">{error.fields.title}</span>}
      </label>
      <label>
        Downloads
        <select value={downloads} onChange={(e) => setDownloads(e.target.value as GalleryDownloads)}>
          {GALLERY_DOWNLOADS.map((d) => (
            <option key={d} value={d}>
              {downloadLabels[d]}
            </option>
          ))}
        </select>
      </label>
      {error.message && <p role="alert">{error.message}</p>}
      <span>
        <button type="submit" disabled={saving}>
          Create gallery
        </button>{' '}
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
      </span>
    </form>
  )
}
