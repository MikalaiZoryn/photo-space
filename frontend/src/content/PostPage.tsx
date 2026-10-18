import { useState, type FormEvent } from 'react'
import { ArrowDown, ArrowUp, Crop as CropIcon, Smartphone, Sparkles, Trash2, X } from 'lucide-react'
import type { PhotoAsset } from '../api/assets.ts'
import { listSessionAssets } from '../api/assets.ts'
import {
  deletePost,
  getPost,
  markPosted,
  regeneratePost,
  schedulePost,
  updatePostPhotos,
  type Crop,
  type PostDetail,
} from '../api/content.ts'
import { navigate, paths } from '../routing.ts'
import { formatDay } from '../time.ts'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { ErrorText, Field } from '../ui/Field.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Panel } from '../ui/Panel.tsx'
import { PhotoImage } from '../ui/Photo.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { useLoad } from '../useLoad.ts'
import { todayIn } from './calendar.ts'
import { centredCrop } from './crop.ts'
import { CropEditor } from './CropEditor.tsx'
import { DraftEditor } from './DraftEditor.tsx'
import { formatLabels, postStatusLabels, postStatusTones, stageLabels } from './labels.ts'
import { usePolling } from './usePolling.ts'

type Props = { workspaceId: string; postId: string; timezone: string }

/** One planned post: its photos and crops, its caption and approval, its day, and "posted". */
export function PostPage({ workspaceId, postId, timezone }: Props) {
  const detail = useLoad((signal) => getPost(workspaceId, postId, signal), [workspaceId, postId])
  const loaded = detail.state.kind === 'loaded' ? detail.state.value : null
  usePolling(loaded?.post.regenerating === true, async () => detail.replace(await getPost(workspaceId, postId)))

  if (detail.state.kind === 'loading') return <Loading>Loading the post…</Loading>
  if (detail.state.kind === 'error') return <LoadError error={detail.state.error} what="post" onRetry={detail.reload} />
  return (
    <PostView
      key={`${detail.state.value.post.version}:${detail.state.value.post.draftVersion}:${detail.state.value.draft.status}`}
      workspaceId={workspaceId}
      timezone={timezone}
      detail={detail.state.value}
      onChange={detail.replace}
      reload={detail.reload}
    />
  )
}

function PostView({
  workspaceId,
  timezone,
  detail,
  onChange,
  reload,
}: {
  workspaceId: string
  timezone: string
  detail: PostDetail
  onChange: (detail: PostDetail) => void
  reload: () => void
}) {
  const { post } = detail
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const posted = post.status === 'POSTED'

  async function act(action: () => Promise<unknown>, description: string, after: () => void = reload) {
    setBusy(true)
    setActionError(null)
    try {
      await action()
      after()
    } catch (e) {
      setActionError(toFormError(e, description).message ?? `Could not ${description}.`)
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="post-heading">
      <PageHeader
        id="post-heading"
        title={`${stageLabels[post.stage]} · ${formatLabels[post.format]}`}
        back={{ href: paths.contentPlan(post.sessionId), label: post.sessionTitle }}
        badges={<StatusBadge tone={postStatusTones[post.status]}>{postStatusLabels[post.status]}</StatusBadge>}
        description={
          post.postedOn
            ? `Posted ${formatDay(post.postedOn)}`
            : post.plannedOn
              ? `Planned for ${formatDay(post.plannedOn)}`
              : 'The day is set when the session’s gallery is published, or pick one below.'
        }
        actions={
          post.status !== 'PROPOSED' && (
            <Button asChild>
              <a href={paths.contentPostKit(post.id)}>
                <Smartphone aria-hidden="true" />
                Open post kit
              </a>
            </Button>
          )
        }
      />
      <div className="space-y-6">
        {actionError && <ErrorAlert>{actionError}</ErrorAlert>}
        {post.idea && (
          <Panel id="post-idea-heading" title={post.format === 'REEL' ? 'Reel notes' : 'Story notes'}>
            <p className="text-sm whitespace-pre-wrap">{post.idea}</p>
          </Panel>
        )}
        <PhotosPanel workspaceId={workspaceId} detail={detail} onChange={onChange} disabled={posted} />
        <Panel
          id="post-caption-heading"
          title="Caption and approval"
          actions={
            !posted && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={busy || post.regenerating}
                onClick={() => act(() => regeneratePost(workspaceId, post.id, post.version), 'write a new caption')}
              >
                <Sparkles aria-hidden="true" />
                {post.regenerating ? 'Writing…' : 'Write a new caption'}
              </Button>
            )
          }
        >
          {post.regenerating && (
            <p role="status" className="text-sm text-muted-foreground">
              Writing a new caption from this post&apos;s photos… The current text stays until it arrives.
            </p>
          )}
          {post.failureReason && !post.regenerating && <ErrorText>{post.failureReason}</ErrorText>}
          <DraftEditor
            workspaceId={workspaceId}
            timezone={timezone}
            number={1}
            draft={detail.draft}
            onChange={() => reload()}
          />
        </Panel>
        <SchedulePanel key={post.plannedOn} workspaceId={workspaceId} detail={detail} reload={reload} disabled={posted} />
        <PostedPanel key={post.postedOn} workspaceId={workspaceId} timezone={timezone} detail={detail} reload={reload} />
        {!posted && (
          <div className="flex flex-wrap items-center gap-2">
            {confirmDelete ? (
              <>
                <span className="text-sm">Delete this post and its caption?</span>
                <Button
                  type="button"
                  variant="destructive"
                  disabled={busy}
                  onClick={() =>
                    act(() => deletePost(workspaceId, post.id, post.version), 'delete the post', () =>
                      navigate(paths.contentPlan(post.sessionId)),
                    )
                  }
                >
                  Delete
                </Button>
                <Button type="button" variant="ghost" onClick={() => setConfirmDelete(false)}>
                  Keep it
                </Button>
              </>
            ) : (
              <Button type="button" variant="ghost" onClick={() => setConfirmDelete(true)}>
                <Trash2 aria-hidden="true" />
                Delete post
              </Button>
            )}
          </div>
        )}
      </div>
    </section>
  )
}

type EditablePhoto = { photo: PhotoAsset; crop: Crop; customCrop: boolean }

/** The post's photos in posting order, each with its 4:5 crop. Saving them sends an approved post back to review. */
function PhotosPanel({
  workspaceId,
  detail,
  onChange,
  disabled,
}: {
  workspaceId: string
  detail: PostDetail
  onChange: (detail: PostDetail) => void
  disabled: boolean
}) {
  const { post } = detail
  const [photos, setPhotos] = useState<EditablePhoto[]>(detail.photos)
  const [cropping, setCropping] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)
  const max = post.format === 'REEL' ? 20 : post.format === 'STORY' ? 10 : 10
  const dirty =
    photos.length !== detail.photos.length ||
    photos.some((p, i) => p.photo.id !== detail.photos[i].photo.id || p.customCrop !== detail.photos[i].customCrop ||
      JSON.stringify(p.crop) !== JSON.stringify(detail.photos[i].crop))

  function update(index: number, change: Partial<EditablePhoto>) {
    setPhotos((current) => current.map((p, i) => (i === index ? { ...p, ...change } : p)))
  }

  function swap(index: number, other: number) {
    setPhotos((current) => {
      const next = [...current]
      ;[next[index], next[other]] = [next[other], next[index]]
      return next
    })
  }

  async function save() {
    setSaving(true)
    setError(noFormError)
    try {
      onChange(
        await updatePostPhotos(
          workspaceId,
          post.id,
          post.version,
          photos.map((p) => ({ assetId: p.photo.id, crop: p.customCrop ? p.crop : null })),
        ),
      )
    } catch (e) {
      setError(toFormError(e, 'save the photos'))
      setSaving(false)
    }
  }

  return (
    <Panel id="post-photos-heading" title={`Photos (${photos.length})`}>
      <p className="text-sm text-muted-foreground">
        In posting order, cropped to 4:5 for the feed. The originals are never changed.
        {post.status !== 'PROPOSED' && ' Changing photos or crops needs a new approval.'}
      </p>
      <ol className="space-y-3" aria-label="Post photos">
        {photos.map((p, index) => (
          <li key={p.photo.id} className="space-y-3 rounded-lg border p-3">
            <div className="flex flex-wrap items-center gap-3">
              <span className="text-sm font-medium">{index + 1}.</span>
              <CroppedThumbnail photo={p.photo} crop={p.crop} />
              <span className="min-w-0 flex-1 truncate text-sm">{p.photo.originalFilename}</span>
              {!disabled && (
                <div className="flex flex-wrap gap-1">
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label={`Move ${p.photo.originalFilename} up`}
                    disabled={index === 0}
                    onClick={() => swap(index, index - 1)}
                  >
                    <ArrowUp aria-hidden="true" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label={`Move ${p.photo.originalFilename} down`}
                    disabled={index === photos.length - 1}
                    onClick={() => swap(index, index + 1)}
                  >
                    <ArrowDown aria-hidden="true" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label={`Crop ${p.photo.originalFilename}`}
                    aria-expanded={cropping === p.photo.id}
                    onClick={() => setCropping(cropping === p.photo.id ? null : p.photo.id)}
                  >
                    <CropIcon aria-hidden="true" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label={`Remove ${p.photo.originalFilename}`}
                    disabled={photos.length === 1}
                    onClick={() => setPhotos((current) => current.filter((_, i) => i !== index))}
                  >
                    <X aria-hidden="true" />
                  </Button>
                </div>
              )}
            </div>
            {cropping === p.photo.id && p.photo.previewUrl && p.photo.width && p.photo.height && (
              <div className="max-w-xl">
                <CropEditor
                  src={p.photo.previewUrl}
                  alt={p.photo.originalFilename}
                  width={p.photo.width}
                  height={p.photo.height}
                  crop={p.crop}
                  onChange={(crop) => update(index, { crop, customCrop: true })}
                />
                {p.customCrop && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => update(index, { crop: centredCrop(p.photo.width ?? 4, p.photo.height ?? 5), customCrop: false })}
                  >
                    Reset to centred
                  </Button>
                )}
              </div>
            )}
          </li>
        ))}
      </ol>
      {error.fields.photos && <ErrorText>{error.fields.photos}</ErrorText>}
      {error.message && <ErrorText>{error.message}</ErrorText>}
      {!disabled && (
        <div className="flex flex-wrap gap-2">
          <Button type="button" disabled={!dirty || saving} onClick={save}>
            Save photos
          </Button>
          {dirty && (
            <Button type="button" variant="ghost" onClick={() => setPhotos(detail.photos)}>
              Undo changes
            </Button>
          )}
          {!adding && photos.length < max && (
            <Button type="button" variant="outline" onClick={() => setAdding(true)}>
              Add photos
            </Button>
          )}
        </div>
      )}
      {adding && (
        <PhotoAdder
          workspaceId={workspaceId}
          sessionId={post.sessionId}
          exclude={photos.map((p) => p.photo.id)}
          onPick={(photo) => {
            if (photos.length < max) {
              setPhotos((current) => [
                ...current,
                { photo, crop: centredCrop(photo.width ?? 4, photo.height ?? 5), customCrop: false },
              ])
            }
          }}
          onClose={() => setAdding(false)}
        />
      )}
    </Panel>
  )
}

/** A small preview of the 4:5 crop, drawn from the thumbnail. */
function CroppedThumbnail({ photo, crop }: { photo: PhotoAsset; crop: Crop }) {
  if (!photo.thumbnailUrl) return <span aria-hidden="true" className="h-15 w-12 rounded bg-muted" />
  return (
    <span aria-hidden="true" className="relative block h-15 w-12 shrink-0 overflow-hidden rounded bg-muted">
      <img
        src={photo.thumbnailUrl}
        alt=""
        className="absolute max-w-none"
        style={{
          width: `${100 / crop.width}%`,
          height: `${100 / crop.height}%`,
          left: `${(-crop.x / crop.width) * 100}%`,
          top: `${(-crop.y / crop.height) * 100}%`,
        }}
      />
    </span>
  )
}

function PhotoAdder({
  workspaceId,
  sessionId,
  exclude,
  onPick,
  onClose,
}: {
  workspaceId: string
  sessionId: string
  exclude: string[]
  onPick: (photo: PhotoAsset) => void
  onClose: () => void
}) {
  const photos = useLoad((signal) => listSessionAssets(workspaceId, sessionId, { size: 100 }, signal), [workspaceId, sessionId])
  if (photos.state.kind === 'loading') return <Loading>Loading the session&apos;s photos…</Loading>
  if (photos.state.kind === 'error') return <LoadError error={photos.state.error} what="photos" onRetry={photos.reload} />
  const available = photos.state.value.items.filter((p) => p.status === 'READY' && !exclude.includes(p.id))
  return (
    <div className="space-y-3 rounded-lg border bg-muted/40 p-3">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm font-medium">Add photos from this session</p>
        <Button type="button" variant="ghost" size="sm" onClick={onClose}>
          Done
        </Button>
      </div>
      {available.length === 0 ? (
        <p className="text-sm text-muted-foreground">Every ready photo is already in this post.</p>
      ) : (
        <ul className="grid grid-cols-3 gap-2 sm:grid-cols-5" aria-label="Photos to add">
          {available.map((photo) => (
            <li key={photo.id}>
              <button
                type="button"
                aria-label={`Add ${photo.originalFilename}`}
                onClick={() => onPick(photo)}
                className="block w-full overflow-hidden rounded-lg border"
              >
                {photo.thumbnailUrl && <PhotoImage src={photo.thumbnailUrl} alt="" />}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function SchedulePanel({
  workspaceId,
  detail,
  reload,
  disabled,
}: {
  workspaceId: string
  detail: PostDetail
  reload: () => void
  disabled: boolean
}) {
  const { post } = detail
  const [day, setDay] = useState(post.plannedOn ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)

  async function save(plannedOn: string | null) {
    setSaving(true)
    setError(noFormError)
    try {
      await schedulePost(workspaceId, post.id, post.version, plannedOn)
      reload()
    } catch (e) {
      setError(toFormError(e, 'save the day'))
      setSaving(false)
    }
  }

  return (
    <Panel id="post-day-heading" title="Day to post">
      <form
        className="flex flex-wrap items-end gap-3"
        aria-label="Day to post"
        onSubmit={(e: FormEvent) => {
          e.preventDefault()
          void save(day || null)
        }}
      >
        <Field label="Day" error={error.fields.plannedOn}>
          <Input type="date" value={day} disabled={disabled} onChange={(e) => setDay(e.target.value)} />
        </Field>
        <Button type="submit" variant="secondary" disabled={disabled || saving || day === (post.plannedOn ?? '')}>
          Save day
        </Button>
        {post.plannedOn && !disabled && (
          <Button type="button" variant="ghost" disabled={saving} onClick={() => save(null)}>
            Clear
          </Button>
        )}
      </form>
      {error.message && <ErrorText>{error.message}</ErrorText>}
    </Panel>
  )
}

function PostedPanel({
  workspaceId,
  timezone,
  detail,
  reload,
}: {
  workspaceId: string
  timezone: string
  detail: PostDetail
  reload: () => void
}) {
  const { post } = detail
  const [postedOn, setPostedOn] = useState(post.postedOn ?? todayIn(timezone))
  const [postUrl, setPostUrl] = useState(post.postUrl ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)

  async function save(posted: boolean) {
    setSaving(true)
    setError(noFormError)
    try {
      await markPosted(workspaceId, post.id, post.version, posted ? { postedOn, postUrl: postUrl.trim() || null } : null)
      reload()
    } catch (e) {
      setError(toFormError(e, posted ? 'mark the post as posted' : 'mark the post as not posted'))
      setSaving(false)
    }
  }

  if (post.status === 'PROPOSED') {
    return (
      <Panel id="post-posted-heading" title="Posting">
        <p className="text-sm text-muted-foreground">Approve the caption to open the post kit and post it by hand.</p>
      </Panel>
    )
  }
  return (
    <Panel id="post-posted-heading" title="Posting">
      {post.postedOn ? (
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <span>
            Posted {formatDay(post.postedOn)}
            {post.postUrl && (
              <>
                {' · '}
                <a href={post.postUrl} target="_blank" rel="noreferrer noopener" className="font-medium underline">
                  View on Instagram
                </a>
              </>
            )}
          </span>
          <Button type="button" variant="ghost" size="sm" disabled={saving} onClick={() => save(false)}>
            Mark as not posted
          </Button>
        </div>
      ) : (
        <form
          className="flex flex-wrap items-end gap-3"
          aria-label="Mark as posted"
          onSubmit={(e: FormEvent) => {
            e.preventDefault()
            void save(true)
          }}
        >
          <Field label="Posted on" error={error.fields.postedOn}>
            <Input type="date" value={postedOn} onChange={(e) => setPostedOn(e.target.value)} required />
          </Field>
          <Field label="Link to the post (optional)" error={error.fields.postUrl} className="min-w-64 flex-1">
            <Input type="url" value={postUrl} placeholder="https://www.instagram.com/p/…" onChange={(e) => setPostUrl(e.target.value)} />
          </Field>
          <Button type="submit" disabled={saving}>
            Mark as posted
          </Button>
        </form>
      )}
      {error.message && <ErrorText>{error.message}</ErrorText>}
    </Panel>
  )
}
