import { useState, type FormEvent } from 'react'
import { listSessionAssets, type PhotoAsset } from '../api/assets.ts'
import { createBatch, getContentSettings, type ContentSettings } from '../api/content.ts'
import { navigate, paths } from '../routing.ts'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { useLoad } from '../useLoad.ts'
import { Check, ShieldCheck, Sparkles } from 'lucide-react'
import { cn } from 'cn'
import { Button } from '@/components/ui/button'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { EmptyState, Loading, photoGrid } from '../ui/Feedback.tsx'
import { CheckboxField, ErrorText, Field } from '../ui/Field.tsx'
import { Panel } from '../ui/Panel.tsx'
import { BatchList } from './BatchList.tsx'

/** Photos the picker loads; a batch takes far fewer. */
const PICKER_SIZE = 100

type Props = {
  workspaceId: string
  sessionId: string
  /** The session's own notes, offered as the starting text. */
  sessionNotes: string | null
  timezone: string
  archived: boolean
}

/** A session's Instagram drafts, and the form that requests new ones from its photos. */
export function SessionContent({ workspaceId, sessionId, sessionNotes, timezone, archived }: Props) {
  const [creating, setCreating] = useState(false)

  return (
    <Panel
      id="session-content-heading"
      title="Instagram drafts"
      actions={
        !archived &&
        !creating && (
          <Button type="button" size="sm" variant="outline" onClick={() => setCreating(true)}>
            <Sparkles aria-hidden="true" />
            Create drafts
          </Button>
        )
      }
    >
      {creating && (
        <NewContentForm
          workspaceId={workspaceId}
          sessionId={sessionId}
          sessionNotes={sessionNotes}
          onCancel={() => setCreating(false)}
        />
      )}
      <BatchList workspaceId={workspaceId} timezone={timezone} sessionId={sessionId} emptyText="No drafts for this session yet." />
    </Panel>
  )
}

function NewContentForm({
  workspaceId,
  sessionId,
  sessionNotes,
  onCancel,
}: {
  workspaceId: string
  sessionId: string
  sessionNotes: string | null
  onCancel: () => void
}) {
  const data = useLoad(
    (signal) =>
      Promise.all([
        getContentSettings(workspaceId, signal),
        listSessionAssets(workspaceId, sessionId, { size: PICKER_SIZE }, signal),
      ]),
    [workspaceId, sessionId],
  )

  if (data.state.kind === 'loading') return <Loading>Loading the session's photos…</Loading>
  if (data.state.kind === 'error') return <LoadError error={data.state.error} what="session's photos" onRetry={data.reload} />
  const [settings, assets] = data.state.value
  const ready = assets.items.filter((photo) => photo.status === 'READY')
  if (ready.length === 0) {
    return (
      <EmptyState action={<Button type="button" variant="ghost" onClick={onCancel}>Close</Button>}>
        Upload photos to this session first. Drafts are written from ready photos.
      </EmptyState>
    )
  }
  return (
    <ContentRequestForm
      workspaceId={workspaceId}
      sessionId={sessionId}
      sessionNotes={sessionNotes}
      settings={settings}
      photos={ready}
      onCancel={onCancel}
    />
  )
}

function ContentRequestForm({
  workspaceId,
  sessionId,
  sessionNotes,
  settings,
  photos,
  onCancel,
}: {
  workspaceId: string
  sessionId: string
  sessionNotes: string | null
  settings: ContentSettings
  photos: PhotoAsset[]
  onCancel: () => void
}) {
  const [selected, setSelected] = useState<string[]>([])
  const [notes, setNotes] = useState(sessionNotes ?? '')
  const [captionCount, setCaptionCount] = useState(Math.min(3, settings.maxCaptions))
  const [includeHashtags, setIncludeHashtags] = useState(true)
  const [includeCallToAction, setIncludeCallToAction] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)
  const full = selected.length >= settings.maxPhotos

  function toggle(id: string) {
    setSelected((current) =>
      current.includes(id) ? current.filter((x) => x !== id) : current.length < settings.maxPhotos ? [...current, id] : current,
    )
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    setError(noFormError)
    try {
      const created = await createBatch(workspaceId, {
        sessionId,
        assetIds: selected,
        notes: notes.trim() || null,
        captionCount,
        includeHashtags,
        includeCallToAction,
      })
      navigate(paths.contentBatch(created.batch.id))
    } catch (e) {
      setError(toFormError(e, 'request drafts'))
      setSaving(false)
    }
  }

  return (
    <form className="space-y-5 rounded-lg border bg-muted/40 p-4" onSubmit={submit} aria-label="New drafts">
      <fieldset className="space-y-3">
        <legend className="text-sm font-medium">
          Photos ({selected.length} of up to {settings.maxPhotos} selected, in the order you pick them)
        </legend>
        <ul className={photoGrid} aria-label="Photos to write about">
          {photos.map((photo) => {
            const index = selected.indexOf(photo.id)
            const on = index >= 0
            return (
              <li key={photo.id}>
                <button
                  type="button"
                  aria-pressed={on}
                  aria-label={on ? `${photo.originalFilename}, selected ${index + 1}` : photo.originalFilename}
                  disabled={!on && full}
                  onClick={() => toggle(photo.id)}
                  className={cn(
                    'relative block w-full overflow-hidden rounded-xl border bg-card text-left shadow-xs outline-none transition focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50',
                    on && 'ring-3 ring-brand',
                  )}
                >
                  {photo.thumbnailUrl ? (
                    <img src={photo.thumbnailUrl} alt="" loading="lazy" className="block aspect-square w-full object-cover" />
                  ) : (
                    <span className="block aspect-square bg-muted" />
                  )}
                  <span className="block truncate px-2.5 py-2 text-xs text-muted-foreground">{photo.originalFilename}</span>
                  {on && (
                    <span className="absolute top-2 left-2 flex size-7 items-center justify-center rounded-full bg-brand text-xs font-semibold text-brand-foreground">
                      {index + 1}
                    </span>
                  )}
                </button>
              </li>
            )
          })}
        </ul>
        {error.fields.assetIds && <ErrorText>Photos {error.fields.assetIds}</ErrorText>}
      </fieldset>
      <Field label="Notes for the AI" error={error.fields.notes}>
        <Textarea
          value={notes}
          maxLength={4000}
          rows={4}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="What happened, the mood, anything worth mentioning. Only what you write here is sent."
        />
      </Field>
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <Field label="Caption options">
          <NativeSelect value={String(captionCount)} onChange={(e) => setCaptionCount(Number(e.target.value))}>
            {Array.from({ length: settings.maxCaptions }, (_, i) => i + 1).map((n) => (
              <NativeSelectOption key={n} value={String(n)}>
                {n}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
        <CheckboxField label="Hashtags" checked={includeHashtags} onChange={setIncludeHashtags} />
        <CheckboxField label="Call to action" checked={includeCallToAction} onChange={setIncludeCallToAction} />
      </div>
      <p className="flex gap-2 rounded-lg border bg-card p-3 text-sm text-muted-foreground">
        <ShieldCheck className="mt-0.5 size-4 shrink-0 text-accent-foreground" aria-hidden="true" />
        {settings.provider === 'mock' ? (
          <span>
            Local mock provider: sample text is written on this server and nothing is sent to an AI service.
          </span>
        ) : (
          <span>
            Sent to the AI provider ({settings.model}): web-size copies of the selected photos (without location or
            camera data), the notes above, the session type, and your brand profile. Client names and contact
            details are not sent. Drafts are only suggestions until you approve them.
          </span>
        )}
      </p>
      {error.message && <ErrorText>{error.message}</ErrorText>}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={saving || selected.length === 0}>
          <Check aria-hidden="true" />
          Write drafts
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </form>
  )
}
