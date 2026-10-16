import { useState } from 'react'
import { createLink, listLinks, revokeLink, type Gallery } from '../api/galleries.ts'
import { publicGalleryHash } from '../routing.ts'
import { formatDate } from '../time.ts'
import { toFormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { Pager } from '../ui/Pager.tsx'
import { useLoad } from '../useLoad.ts'
import { linkStateLabels } from './labels.ts'

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
    <section aria-labelledby="links-heading">
      <h4 id="links-heading">Share links</h4>
      {!archived && (
        <div className="toolbar filters">
          <label>
            Valid for{' '}
            <select value={validDays} onChange={(e) => setValidDays(Number(e.target.value))}>
              {VALIDITY_DAYS.map((days) => (
                <option key={days} value={days}>
                  {days} days
                </option>
              ))}
            </select>
          </label>
          <button type="button" disabled={busy} onClick={create}>
            Create link
          </button>
        </div>
      )}
      {error && <p role="alert">{error}</p>}
      {newUrl && (
        <div className="new-link" role="status">
          <p>Copy this link now and send it to your client. It is not shown again; create a new link if it gets lost.</p>
          <span className="toolbar">
            <input aria-label="New share link" value={newUrl} readOnly onFocus={(e) => e.target.select()} />
            <button type="button" onClick={() => copy(newUrl)}>
              {copied ? 'Copied' : 'Copy'}
            </button>
            <button type="button" onClick={() => setNewUrl(null)}>
              Done
            </button>
          </span>
        </div>
      )}
      {links.state.kind === 'loading' && <p role="status">Loading links…</p>}
      {links.state.kind === 'error' && <LoadError error={links.state.error} what="links" onRetry={links.reload} />}
      {links.state.kind === 'loaded' &&
        (links.state.value.totalItems === 0 ? (
          <p className="muted">No links yet.</p>
        ) : (
          <>
            <ul className="records" aria-label="Share links">
              {links.state.value.items.map((link) => (
                <li key={link.id}>
                  <span className={`badge link-${link.state.toLowerCase()}`}>{linkStateLabels[link.state]}</span>
                  <span>
                    Created {formatDate(link.createdAt, timezone)} ·{' '}
                    {link.state === 'REVOKED' && link.revokedAt
                      ? `revoked ${formatDate(link.revokedAt, timezone)}`
                      : `${link.state === 'EXPIRED' ? 'expired' : 'expires'} ${formatDate(link.expiresAt, timezone)}`}
                  </span>
                  <span className="muted">
                    {link.views} {link.views === 1 ? 'view' : 'views'} · {link.downloads}{' '}
                    {link.downloads === 1 ? 'download' : 'downloads'}
                    {link.lastAccessedAt && ` · last used ${formatDate(link.lastAccessedAt, timezone)}`}
                  </span>
                  {link.state === 'ACTIVE' && (
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => run(() => revokeLink(workspaceId, gallery.id, link.id).then(() => {}), 'revoke the link')}
                    >
                      Revoke
                    </button>
                  )}
                </li>
              ))}
            </ul>
            <Pager page={links.state.value} onPage={setPageNumber} />
          </>
        ))}
    </section>
  )
}
