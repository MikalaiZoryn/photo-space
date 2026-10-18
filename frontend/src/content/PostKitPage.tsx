import { useState } from 'react'
import { Check, Copy, Download } from 'lucide-react'
import { getPostKit, postText, type PostKit } from '../api/content.ts'
import { paths } from '../routing.ts'
import { formatDay } from '../time.ts'
import { Button } from '@/components/ui/button'
import { ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Panel } from '../ui/Panel.tsx'
import { useLoad } from '../useLoad.ts'
import { formatLabels, stageLabels } from './labels.ts'

/** Time between downloads, so the browser does not drop all but the first. */
const DOWNLOAD_GAP_MS = 400

/**
 * Everything needed to post an approved post by hand, laid out for a phone: the photos in order as 4:5 exports
 * (and the originals), and the text to copy. Download links expire after a few minutes; reopen the kit for new
 * ones. Mark the post as posted on its page afterwards.
 */
export function PostKitPage({ workspaceId, postId }: { workspaceId: string; postId: string }) {
  const kit = useLoad((signal) => getPostKit(workspaceId, postId, signal), [workspaceId, postId])

  if (kit.state.kind === 'loading') return <Loading>Preparing the post kit…</Loading>
  if (kit.state.kind === 'error') return <LoadError error={kit.state.error} what="post kit" onRetry={kit.reload} />
  return <Kit kit={kit.state.value} />
}

function Kit({ kit }: { kit: PostKit }) {
  const { post } = kit
  const [copied, setCopied] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const pieces = [
    { id: 'all', label: 'Copy caption and hashtags', text: postText(post) },
    ...(post.hashtags.length > 0
      ? [{ id: 'hashtags', label: 'Copy hashtags only', text: post.hashtags.map((tag) => `#${tag}`).join(' ') }]
      : []),
  ]

  async function copy(id: string, text: string) {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(id)
      setError(null)
    } catch {
      setError('Could not copy. Select the text and copy it yourself.')
    }
  }

  // Each link answers with an attachment, so clicking it saves the file and the page stays open.
  function downloadAll() {
    kit.photos.forEach((photo, index) =>
      setTimeout(() => {
        const link = document.createElement('a')
        link.href = photo.exportUrl
        link.click()
      }, index * DOWNLOAD_GAP_MS),
    )
  }

  return (
    <section aria-labelledby="kit-heading" className="mx-auto max-w-xl">
      <PageHeader
        id="kit-heading"
        title="Post kit"
        back={{ href: paths.contentPost(post.id), label: `${stageLabels[post.stage]} · ${formatLabels[post.format]}` }}
        description={`${post.sessionTitle}${post.plannedOn ? ` · planned for ${formatDay(post.plannedOn)}` : ''}`}
      />
      <div className="space-y-6">
        {error && <ErrorAlert>{error}</ErrorAlert>}
        <Panel
          id="kit-photos-heading"
          title={`Photos (${kit.photos.length})`}
          actions={
            kit.photos.length > 1 && (
              <Button type="button" variant="outline" size="sm" onClick={downloadAll}>
                <Download aria-hidden="true" />
                Save all
              </Button>
            )
          }
        >
          <p className="text-sm text-muted-foreground">
            4:5 versions, in posting order. In Instagram, add them in the same order.
          </p>
          <ol className="space-y-4" aria-label="Photos in posting order">
            {kit.photos.map((photo, index) => (
              <li key={photo.assetId} className="space-y-2">
                <img src={photo.exportUrl} alt={`${index + 1}. ${photo.filename}`} className="w-full rounded-lg bg-muted" />
                <div className="flex flex-wrap items-center gap-2 text-sm">
                  <span className="min-w-0 flex-1 truncate">
                    {index + 1}. {photo.filename}
                  </span>
                  <Button asChild size="sm" variant="secondary">
                    <a href={photo.exportUrl} download>
                      <Download aria-hidden="true" />
                      Save
                    </a>
                  </Button>
                  {photo.originalUrl && (
                    <Button asChild size="sm" variant="ghost">
                      <a href={photo.originalUrl} download>
                        Original
                      </a>
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ol>
        </Panel>
        <Panel id="kit-text-heading" title="Text">
          <p className="text-sm whitespace-pre-wrap">{postText(post)}</p>
          <div className="flex flex-wrap gap-2">
            {pieces.map((piece) => (
              <Button key={piece.id} type="button" variant={piece.id === 'all' ? 'default' : 'outline'} onClick={() => copy(piece.id, piece.text)}>
                {copied === piece.id ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
                {copied === piece.id ? 'Copied' : piece.label}
              </Button>
            ))}
          </div>
        </Panel>
        {post.idea && (
          <Panel id="kit-idea-heading" title={post.format === 'REEL' ? 'Reel notes' : 'Story notes'}>
            <p className="text-sm whitespace-pre-wrap">{post.idea}</p>
          </Panel>
        )}
        <p className="text-sm text-muted-foreground">
          Posted it?{' '}
          <a href={paths.contentPost(post.id)} className="font-medium underline">
            Mark it as posted
          </a>{' '}
          so the calendar counts it.
        </p>
      </div>
    </section>
  )
}
