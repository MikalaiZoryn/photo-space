import { useState, type FormEvent } from 'react'
import { CalendarRange, LoaderCircle, Plus, RefreshCw, RotateCcw, TriangleAlert } from 'lucide-react'
import { ApiError } from '../api/client.ts'
import { listSessionAssets } from '../api/assets.ts'
import {
  addPost,
  getPlan,
  MAX_HASHTAGS,
  parseHashtags,
  requestPlan,
  retryPlan,
  type PlanDetail,
  type PostFormat,
  type PostStage,
} from '../api/content.ts'
import { navigate, paths } from '../routing.ts'
import { formatDay } from '../time.ts'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NativeSelect } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { EmptyState, ErrorAlert, Loading, photoGrid } from '../ui/Feedback.tsx'
import { ErrorText, Field } from '../ui/Field.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Panel } from '../ui/Panel.tsx'
import { PhotoImage } from '../ui/Photo.tsx'
import { RecordList } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { useLoad } from '../useLoad.ts'
import { batchStatusLabels, batchStatusTones, formatLabels, stageLabels } from './labels.ts'
import { PostRow } from './PostRow.tsx'
import { usePolling } from './usePolling.ts'

const STAGES: PostStage[] = ['SNEAK_PEEK', 'DELIVERY', 'DRIP']

type Props = { workspaceId: string; sessionId: string }

/** A session's plan: its posts by stage, its generation's progress or failure, and refreshing or adding to it. */
export function PlanPage({ workspaceId, sessionId }: Props) {
  const plan = useLoad(
    (signal) =>
      getPlan(workspaceId, sessionId, signal).catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 404) return null
        throw e
      }),
    [workspaceId, sessionId],
  )
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const detail = plan.state.kind === 'loaded' ? plan.state.value : null
  usePolling(detail?.plan.status === 'PENDING', async () => plan.replace(await getPlan(workspaceId, sessionId)))

  async function run(action: () => Promise<PlanDetail>, description: string) {
    setBusy(true)
    setActionError(null)
    try {
      plan.replace(await action())
    } catch (e) {
      setActionError(toFormError(e, description).message ?? `Could not ${description}.`)
    } finally {
      setBusy(false)
    }
  }

  if (plan.state.kind === 'loading') return <Loading>Loading the plan…</Loading>
  if (plan.state.kind === 'error') return <LoadError error={plan.state.error} what="plan" onRetry={plan.reload} />
  if (detail === null) {
    return (
      <section aria-labelledby="plan-heading">
        <PageHeader id="plan-heading" title="Content plan" back={{ href: paths.session(sessionId), label: 'Session' }} />
        {actionError && <ErrorAlert>{actionError}</ErrorAlert>}
        <EmptyState
          action={
            <Button type="button" disabled={busy} onClick={() => run(() => requestPlan(workspaceId, sessionId), 'plan posts')}>
              <CalendarRange aria-hidden="true" />
              Plan posts
            </Button>
          }
        >
          This session has no plan yet. One is proposed by itself once its photos are ready.
        </EmptyState>
      </section>
    )
  }

  const { plan: current, posts } = detail
  const pending = current.status === 'PENDING'
  return (
    <section aria-labelledby="plan-heading">
      <PageHeader
        id="plan-heading"
        title="Content plan"
        back={{ href: paths.session(sessionId), label: current.sessionTitle }}
        badges={
          current.status !== 'SUCCEEDED' && (
            <StatusBadge tone={batchStatusTones[current.status]}>{batchStatusLabels[current.status]}</StatusBadge>
          )
        }
        description={
          current.publishedOn
            ? `Gallery published ${formatDay(current.publishedOn)}: delivery and later posts count from that day.`
            : 'Delivery and later posts get their days when the session’s gallery is published.'
        }
        actions={
          !pending && (
            <Button
              type="button"
              variant="outline"
              disabled={busy}
              title="Replaces proposals nobody touched; keeps what you edited, approved, or posted"
              onClick={() => run(() => requestPlan(workspaceId, sessionId), 'refresh the plan')}
            >
              <RefreshCw aria-hidden="true" />
              Refresh plan
            </Button>
          )
        }
      />
      <div className="space-y-6">
        {actionError && <ErrorAlert>{actionError}</ErrorAlert>}
        {pending && (
          <div role="status" className="flex items-start gap-3 rounded-xl border bg-card p-4 text-sm shadow-xs">
            <LoaderCircle className="mt-0.5 size-4 shrink-0 animate-spin text-accent-foreground" aria-hidden="true" />
            <div className="space-y-1">
              <p className="font-medium">Planning posts… This usually takes under a minute.</p>
              {current.failureReason && (
                <p className="text-muted-foreground">Retrying automatically after a problem: {current.failureReason}</p>
              )}
            </div>
          </div>
        )}
        {current.status === 'FAILED' && (
          <ErrorAlert>
            <span className="flex flex-wrap items-center gap-x-3 gap-y-2">
              <span>Planning failed. {current.failureReason}</span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={busy}
                onClick={() => run(() => retryPlan(workspaceId, current.id), 'retry')}
              >
                <RotateCcw aria-hidden="true" />
                Retry
              </Button>
            </span>
          </ErrorAlert>
        )}
        {current.warnings.length > 0 && !pending && (
          <div className="flex gap-3 rounded-xl border border-tone-warning/30 bg-tone-warning-bg p-4 text-sm text-tone-warning">
            <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
            <div>
              <p className="font-medium">Check before approving</p>
              <ul className="mt-1 list-disc space-y-1 pl-5" aria-label="Warnings">
                {current.warnings.map((warning) => (
                  <li key={warning}>{warning}</li>
                ))}
              </ul>
            </div>
          </div>
        )}
        {posts.length > 0 && (
          <p className="text-sm text-muted-foreground">
            These are suggestions. Open each post to check its photos and crops, edit the caption, and approve it.
            Nothing is posted automatically.
          </p>
        )}
        {STAGES.map((stage) => {
          const inStage = posts.filter((post) => post.stage === stage)
          if (inStage.length === 0) return null
          return (
            <Panel key={stage} id={`plan-stage-${stage}`} title={stageLabels[stage]}>
              <RecordList label={stageLabels[stage]}>
                {inStage.map((post) => (
                  <PostRow key={post.id} post={post} showSession={false} />
                ))}
              </RecordList>
            </Panel>
          )
        })}
        {!pending && <AddPostPanel workspaceId={workspaceId} sessionId={sessionId} planId={current.id} />}
        {current.provider && (
          <p className="text-xs text-muted-foreground">
            Written by {current.provider} ({current.model}).
          </p>
        )}
      </div>
    </section>
  )
}

const FORMATS: PostFormat[] = ['SINGLE', 'REEL', 'STORY']

/** A post the photographer writes themselves, added to the plan like any other. */
function AddPostPanel({ workspaceId, sessionId, planId }: { workspaceId: string; sessionId: string; planId: string }) {
  const [open, setOpen] = useState(false)
  return (
    <Panel
      id="add-post-heading"
      title="Add a post"
      actions={
        !open && (
          <Button type="button" variant="outline" size="sm" onClick={() => setOpen(true)}>
            <Plus aria-hidden="true" />
            Add a post
          </Button>
        )
      }
    >
      {open ? (
        <AddPostForm workspaceId={workspaceId} sessionId={sessionId} planId={planId} onCancel={() => setOpen(false)} />
      ) : (
        <p className="text-sm text-muted-foreground">Write your own post from this session&apos;s photos.</p>
      )}
    </Panel>
  )
}

function AddPostForm({
  workspaceId,
  sessionId,
  planId,
  onCancel,
}: {
  workspaceId: string
  sessionId: string
  planId: string
  onCancel: () => void
}) {
  const photos = useLoad((signal) => listSessionAssets(workspaceId, sessionId, { size: 100 }, signal), [workspaceId, sessionId])
  const [stage, setStage] = useState<PostStage>('DRIP')
  const [format, setFormat] = useState<PostFormat>('SINGLE')
  const [selected, setSelected] = useState<string[]>([])
  const [caption, setCaption] = useState('')
  const [callToAction, setCallToAction] = useState('')
  const [hashtags, setHashtags] = useState('')
  const [plannedOn, setPlannedOn] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)
  const maxPhotos = format === 'REEL' ? 20 : 10
  const tags = parseHashtags(hashtags)

  function toggle(id: string) {
    setSelected((current) =>
      current.includes(id) ? current.filter((x) => x !== id) : current.length < maxPhotos ? [...current, id] : current,
    )
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    setError(noFormError)
    try {
      const created = await addPost(workspaceId, planId, {
        stage,
        format: format === 'SINGLE' && selected.length > 1 ? 'CAROUSEL' : format,
        photos: selected,
        draft: { caption: caption.trim(), callToAction: callToAction.trim() || null, hashtags: tags },
        idea: null,
        plannedOn: plannedOn || null,
      })
      navigate(paths.contentPost(created.post.id))
    } catch (e) {
      setError(toFormError(e, 'add the post'))
      setSaving(false)
    }
  }

  if (photos.state.kind === 'loading') return <Loading>Loading the session&apos;s photos…</Loading>
  if (photos.state.kind === 'error') return <LoadError error={photos.state.error} what="photos" onRetry={photos.reload} />
  const ready = photos.state.value.items.filter((photo) => photo.status === 'READY')
  return (
    <form className="space-y-5" onSubmit={submit} aria-label="New post">
      <div className="grid gap-5 md:grid-cols-3">
        <Field label="Stage">
          <NativeSelect value={stage} onChange={(e) => setStage(e.target.value as PostStage)} className="w-full">
            {STAGES.map((s) => (
              <option key={s} value={s}>
                {stageLabels[s]}
              </option>
            ))}
          </NativeSelect>
        </Field>
        <Field label="Format">
          <NativeSelect value={format} onChange={(e) => setFormat(e.target.value as PostFormat)} className="w-full">
            {FORMATS.map((f) => (
              <option key={f} value={f}>
                {f === 'SINGLE' ? 'Photo or carousel' : formatLabels[f]}
              </option>
            ))}
          </NativeSelect>
        </Field>
        <Field label="Day" error={error.fields.plannedOn}>
          <Input type="date" value={plannedOn} onChange={(e) => setPlannedOn(e.target.value)} />
        </Field>
      </div>
      <fieldset className="space-y-3">
        <legend className="text-sm font-medium">
          Photos ({selected.length} of up to {maxPhotos}, in the order you pick them)
        </legend>
        <ul className={photoGrid} aria-label="Photos for the post">
          {ready.map((photo) => {
            const index = selected.indexOf(photo.id)
            return (
              <li key={photo.id}>
                <button
                  type="button"
                  aria-pressed={index >= 0}
                  aria-label={index >= 0 ? `${photo.originalFilename}, selected ${index + 1}` : photo.originalFilename}
                  onClick={() => toggle(photo.id)}
                  className={`relative block w-full overflow-hidden rounded-xl border ${index >= 0 ? 'ring-3 ring-brand' : ''}`}
                >
                  {photo.thumbnailUrl && <PhotoImage src={photo.thumbnailUrl} alt="" />}
                  {index >= 0 && (
                    <span className="absolute top-1.5 left-1.5 flex size-6 items-center justify-center rounded-full bg-brand text-xs font-semibold text-brand-foreground">
                      {index + 1}
                    </span>
                  )}
                </button>
              </li>
            )
          })}
        </ul>
        {error.fields.photos && <ErrorText>{error.fields.photos}</ErrorText>}
      </fieldset>
      <Field label="Caption" error={error.fields['draft.caption'] ?? error.fields.caption}>
        <Textarea value={caption} rows={4} maxLength={2200} onChange={(e) => setCaption(e.target.value)} required />
      </Field>
      <div className="grid gap-5 md:grid-cols-2">
        <Field label="Call to action">
          <Input value={callToAction} maxLength={300} onChange={(e) => setCallToAction(e.target.value)} />
        </Field>
        <Field
          label="Hashtags"
          error={
            error.fields['draft.hashtags'] ??
            error.fields.hashtags ??
            (tags.length > MAX_HASHTAGS ? `Instagram allows at most ${MAX_HASHTAGS} hashtags.` : undefined)
          }
        >
          <Input value={hashtags} onChange={(e) => setHashtags(e.target.value)} placeholder="#family #krakow" />
        </Field>
      </div>
      {error.message && <ErrorText>{error.message}</ErrorText>}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={saving || selected.length === 0 || tags.length > MAX_HASHTAGS}>
          Add post
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </form>
  )
}
