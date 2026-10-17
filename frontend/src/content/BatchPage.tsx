import { useEffect, useEffectEvent, useState } from 'react'
import { getBatch, regenerateBatch, retryBatch, type BatchDetail, type ContentDraft } from '../api/content.ts'
import { navigate, paths } from '../routing.ts'
import { formatDate } from '../time.ts'
import { toFormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { useLoad } from '../useLoad.ts'
import { LoaderCircle, RefreshCw, RotateCcw, TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ErrorAlert, Loading, photoGrid } from '../ui/Feedback.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Panel } from '../ui/Panel.tsx'
import { PhotoFigure, PhotoImage } from '../ui/Photo.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { DraftEditor } from './DraftEditor.tsx'
import { batchStatusLabels, batchStatusTones } from './labels.ts'

/** How often a pending batch is checked again. */
export const BATCH_POLL_MS = 2000

type Props = { workspaceId: string; batchId: string; timezone: string }

/** One batch: its progress while generating, then the drafts to edit and approve, and the suggestions. */
export function BatchPage({ workspaceId, batchId, timezone }: Props) {
  const detail = useLoad((signal) => getBatch(workspaceId, batchId, signal), [workspaceId, batchId])

  if (detail.state.kind === 'loading') return <Loading>Loading drafts…</Loading>
  if (detail.state.kind === 'error') return <LoadError error={detail.state.error} what="drafts" onRetry={detail.reload} />
  return <BatchView workspaceId={workspaceId} timezone={timezone} detail={detail.state.value} onChange={detail.replace} />
}

function BatchView({
  workspaceId,
  timezone,
  detail,
  onChange,
}: {
  workspaceId: string
  timezone: string
  detail: BatchDetail
  onChange: (detail: BatchDetail) => void
}) {
  const { batch } = detail
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const pending = batch.status === 'PENDING'

  // Refreshes in place while generating; a failed check leaves the page as it was and the next one tries again.
  const poll = useEffectEvent(async () => {
    try {
      onChange(await getBatch(workspaceId, batch.id))
    } catch {
      // Try again on the next tick.
    }
  })
  useEffect(() => {
    if (!pending) return
    const timer = setInterval(() => void poll(), BATCH_POLL_MS)
    return () => clearInterval(timer)
  }, [pending])

  async function retry() {
    setBusy(true)
    setActionError(null)
    try {
      onChange(await retryBatch(workspaceId, batch.id))
    } catch (e) {
      setActionError(toFormError(e, 'retry').message ?? 'Could not retry.')
    } finally {
      setBusy(false)
    }
  }

  async function regenerate() {
    setBusy(true)
    setActionError(null)
    try {
      const created = await regenerateBatch(workspaceId, batch.id)
      navigate(paths.contentBatch(created.batch.id))
    } catch (e) {
      setActionError(toFormError(e, 'regenerate the drafts').message ?? 'Could not regenerate the drafts.')
      setBusy(false)
    }
  }

  function draftChanged(draft: ContentDraft) {
    onChange({ ...detail, drafts: detail.drafts.map((d) => (d.id === draft.id ? draft : d)) })
  }

  const byId = new Map(detail.photos.map((photo) => [photo.id, photo]))
  const carousel = detail.carousel.flatMap((id) => byId.get(id) ?? [])

  return (
    <section aria-labelledby="batch-heading">
      <PageHeader
        id="batch-heading"
        title="Instagram drafts"
        back={{ href: paths.session(batch.sessionId), label: batch.sessionTitle }}
        badges={<StatusBadge tone={batchStatusTones[batch.status]}>{batchStatusLabels[batch.status]}</StatusBadge>}
        description={`${batch.photoCount} ${batch.photoCount === 1 ? 'photo' : 'photos'} · requested ${formatDate(batch.createdAt, timezone)}`}
        actions={
          !pending && (
            <Button type="button" variant="outline" disabled={busy} onClick={regenerate}>
              <RefreshCw aria-hidden="true" />
              Regenerate
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
              <p className="font-medium">Writing drafts… This usually takes under a minute.</p>
              {batch.failureReason && (
                <p className="text-muted-foreground">Retrying automatically after a problem: {batch.failureReason}</p>
              )}
            </div>
          </div>
        )}
        {batch.status === 'FAILED' && (
          <ErrorAlert>
            <span className="flex flex-wrap items-center gap-x-3 gap-y-2">
              <span>Generation failed. {batch.failureReason}</span>
              <Button type="button" variant="outline" size="sm" disabled={busy} onClick={retry}>
                <RotateCcw aria-hidden="true" />
                Retry
              </Button>
            </span>
          </ErrorAlert>
        )}
        {batch.status === 'SUCCEEDED' && (
          <>
            <p className="text-sm text-muted-foreground">
              These are suggestions. Check every detail, edit freely, and approve the ones you like. Nothing is posted
              automatically: copy an approved draft into Instagram yourself.
            </p>
            {batch.warnings.length > 0 && (
              <div className="flex gap-3 rounded-xl border border-tone-warning/30 bg-tone-warning-bg p-4 text-sm text-tone-warning">
                <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
                <div>
                  <p className="font-medium">Check before approving</p>
                  <ul className="mt-1 list-disc space-y-1 pl-5" aria-label="Warnings">
                    {batch.warnings.map((warning) => (
                      <li key={warning}>{warning}</li>
                    ))}
                  </ul>
                </div>
              </div>
            )}
            <ol className="space-y-4" aria-label="Drafts">
              {detail.drafts.map((draft, index) => (
                <li key={`${draft.id}:${draft.version}`}>
                  <DraftEditor
                    workspaceId={workspaceId}
                    timezone={timezone}
                    number={index + 1}
                    draft={draft}
                    onChange={draftChanged}
                  />
                </li>
              ))}
            </ol>
          </>
        )}
        <Panel id="carousel-heading" title={batch.status === 'SUCCEEDED' ? 'Suggested carousel order' : 'Selected photos'}>
          {batch.status === 'SUCCEEDED' && batch.carouselRationale && (
            <p className="text-sm text-muted-foreground">{batch.carouselRationale}</p>
          )}
          <ol className={photoGrid} aria-label={batch.status === 'SUCCEEDED' ? 'Carousel order' : 'Selected photos'}>
            {(batch.status === 'SUCCEEDED' && carousel.length > 0 ? carousel : detail.photos).map((photo, index) => (
              <li key={photo.id}>
                <PhotoFigure caption={`${index + 1}. ${photo.originalFilename}`}>
                  {photo.thumbnailUrl ? (
                    <PhotoImage src={photo.thumbnailUrl} alt={photo.originalFilename} />
                  ) : (
                    <div className="aspect-square bg-muted" />
                  )}
                </PhotoFigure>
              </li>
            ))}
          </ol>
        </Panel>
        {batch.storyIdeas.length > 0 && (
          <Panel id="story-ideas-heading" title="Story and Reel ideas">
            <ul className="list-disc space-y-1 pl-5 text-sm">
              {batch.storyIdeas.map((idea) => (
                <li key={idea}>{idea}</li>
              ))}
            </ul>
          </Panel>
        )}
        {batch.notes && (
          <Panel id="batch-notes-heading" title="Notes sent">
            <p className="text-sm whitespace-pre-wrap">{batch.notes}</p>
          </Panel>
        )}
        {batch.provider && (
          <p className="text-xs text-muted-foreground">
            Written by {batch.provider} ({batch.model}), prompt {batch.promptVersion}.
          </p>
        )}
      </div>
    </section>
  )
}
