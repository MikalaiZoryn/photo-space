import { useState } from 'react'
import { createLink, listLinks, revokeLink, type Gallery } from '../api/galleries.ts'
import { publicGalleryHash } from '../routing.ts'
import { formatDate } from '../time.ts'
import { toFormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { Pager } from '../ui/Pager.tsx'
import { useLoad } from '../useLoad.ts'
import { Check, Copy, Link2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { EmptyState, ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { Panel } from '../ui/Panel.tsx'
import { RecordList } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { linkStateLabels, linkStateTones } from './labels.ts'

const VALIDITY_DAYS = [7, 30, 90, 365]

type Props = { workspaceId: string; timezone: string; gallery: Gallery }

/**
 * A gallery's share links: create one (its URL is shown once, since only a hash of the token is stored), see
 * how each was used, and revoke them.
 */
export function ShareLinks({ workspaceId, timezone, gallery }: Props) {
  const [pageNumber, setPageNumber] = useState(0)
  const links = useLoad(
    (signal) => listLinks(workspaceId, gallery.id, { page: pageNumber }, signal),
    [workspaceId, gallery.id, pageNumber],
  )
  const [validDays, setValidDays] = useState(30)
  const [newUrl, setNewUrl] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const archived = gallery.archivedAt !== null

  async function run(action: () => Promise<void>, description: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      links.reload()
    } catch (e) {
      setError(toFormError(e, description).message ?? `Could not ${description}.`)
    } finally {
      setBusy(false)
    }
  }

  const create = () =>
    run(async () => {
      const created = await createLink(workspaceId, gallery.id, validDays)
      setCopied(false)
      setNewUrl(`${window.location.origin}${window.location.pathname}${publicGalleryHash(created.token)}`)
    }, 'create the link')

  async function copy(url: string) {
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
    } catch {
      // Clipboard access can be refused; the link stays selectable in the field.
    }
  }

  return (
    <Panel
      id="links-heading"
      title="Share links"
      actions={
        !archived && (
          <div className="flex flex-wrap items-center gap-2">
            <label className="inline-flex items-center gap-2 text-sm">
              Valid for{' '}
              <NativeSelect size="sm" value={validDays} onChange={(e) => setValidDays(Number(e.target.value))}>
                {VALIDITY_DAYS.map((days) => (
                  <NativeSelectOption key={days} value={days}>
                    {days} days
                  </NativeSelectOption>
                ))}
              </NativeSelect>
            </label>
            <Button type="button" size="sm" disabled={busy} onClick={create}>
              <Link2 aria-hidden="true" />
              Create link
            </Button>
          </div>
        )
      }
    >
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {newUrl && (
        <div className="space-y-3 rounded-lg border border-tone-success/30 bg-tone-success-bg p-4" role="status">
          <p className="text-sm text-tone-success">
            Copy this link now and send it to your client. It is not shown again; create a new link if it gets lost.
          </p>
          <div className="flex flex-col gap-2 sm:flex-row">
            <Input
              aria-label="New share link"
              className="flex-1 bg-card font-mono text-xs"
              value={newUrl}
              readOnly
              onFocus={(e) => e.target.select()}
            />
            <div className="flex gap-2">
              <Button type="button" onClick={() => copy(newUrl)}>
                {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
                {copied ? 'Copied' : 'Copy'}
              </Button>
              <Button type="button" variant="outline" onClick={() => setNewUrl(null)}>
                Done
              </Button>
            </div>
          </div>
        </div>
      )}
      {links.state.kind === 'loading' && <Loading>Loading links…</Loading>}
      {links.state.kind === 'error' && <LoadError error={links.state.error} what="links" onRetry={links.reload} />}
      {links.state.kind === 'loaded' &&
        (links.state.value.totalItems === 0 ? (
          <EmptyState>No links yet.</EmptyState>
        ) : (
          <>
            <RecordList label="Share links">
              {links.state.value.items.map((link) => (
                <li key={link.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
                  <StatusBadge tone={linkStateTones[link.state]}>{linkStateLabels[link.state]}</StatusBadge>
                  <div className="min-w-0 flex-1 text-sm">
                    <p>
                      Created {formatDate(link.createdAt, timezone)} ·{' '}
                      {link.state === 'REVOKED' && link.revokedAt
                        ? `revoked ${formatDate(link.revokedAt, timezone)}`
                        : `${link.state === 'EXPIRED' ? 'expired' : 'expires'} ${formatDate(link.expiresAt, timezone)}`}
                    </p>
                    <p className="text-muted-foreground">
                      {link.views} {link.views === 1 ? 'view' : 'views'} · {link.downloads}{' '}
                      {link.downloads === 1 ? 'download' : 'downloads'}
                      {link.lastAccessedAt && ` · last used ${formatDate(link.lastAccessedAt, timezone)}`}
                    </p>
                  </div>
                  {link.state === 'ACTIVE' && (
                    <Button
                      type="button"
                      size="sm"
                      variant="destructive"
                      disabled={busy}
                      onClick={() => run(() => revokeLink(workspaceId, gallery.id, link.id).then(() => {}), 'revoke the link')}
                    >
                      Revoke
                    </Button>
                  )}
                </li>
              ))}
            </RecordList>
            <Pager page={links.state.value} onPage={setPageNumber} />
          </>
        ))}
    </Panel>
  )
}
