import { useState, type FormEvent } from 'react'
import { createGallery, GALLERY_DOWNLOADS, type GalleryDownloads } from '../api/galleries.ts'
import { navigate, paths } from '../routing.ts'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'
import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { ErrorText, Field } from '../ui/Field.tsx'
import { Panel } from '../ui/Panel.tsx'
import { GalleryList } from './GalleryList.tsx'
import { downloadLabels } from './labels.ts'

type Props = { workspaceId: string; sessionId: string; timezone: string; archived: boolean }

/** A session's galleries, and a form to start a new one from its photos. */
export function SessionGalleries({ workspaceId, sessionId, timezone, archived }: Props) {
  const [creating, setCreating] = useState(false)

  return (
    <Panel
      id="session-galleries-heading"
      title="Galleries"
      actions={
        !archived &&
        !creating && (
          <Button type="button" size="sm" variant="outline" onClick={() => setCreating(true)}>
            <Plus aria-hidden="true" />
            New gallery
          </Button>
        )
      }
    >
      {creating && <NewGalleryForm workspaceId={workspaceId} sessionId={sessionId} onCancel={() => setCreating(false)} />}
      <GalleryList
        workspaceId={workspaceId}
        timezone={timezone}
        filter={{ sessionId }}
        emptyText="No galleries for this session yet."
      />
    </Panel>
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
    <form className="grid gap-4 rounded-lg border bg-muted/40 p-4 md:grid-cols-2" onSubmit={submit} aria-label="New gallery">
      <Field label="Title" error={error.fields.title}>
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
      {error.message && (
        <div className="md:col-span-2">
          <ErrorText>{error.message}</ErrorText>
        </div>
      )}
      <div className="flex flex-wrap gap-2 md:col-span-2">
        <Button type="submit" disabled={saving}>
          Create gallery
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </form>
  )
}
