import { useState } from 'react'
import { ApiError } from '../api/client.ts'
import { fetchPublicGallery, requestDownload, type PublicGallery, type PublicPhoto } from '../api/galleries.ts'
import { formatDate } from '../time.ts'
import { startDownload } from '../ui/download.ts'
import { reference } from '../ui/formError.ts'
import { useLoad } from '../useLoad.ts'

/**
 * What a client sees after opening a share link: the one gallery the token opens, without signing in. The
 * token never leaves this page except in the request header the API expects.
 */
export function PublicGalleryPage({ token }: { token: string }) {
  const gallery = useLoad((signal) => fetchPublicGallery(token, signal), [token])

  if (gallery.state.kind === 'loading') return <p role="status">Loading gallery…</p>
  if (gallery.state.kind === 'error') {
    const error = gallery.state.error
    if (error instanceof ApiError && error.status === 404) {
      return (
        <section aria-labelledby="gallery-unavailable-heading">
          <h1 id="gallery-unavailable-heading">This gallery link does not work</h1>
          <p>It may have expired or been revoked, or the gallery is no longer shared. Ask your photographer for a new link.</p>
        </section>
      )
    }
    return (
      <p role="alert">
        Could not load the gallery{reference(error)}.{' '}
        <button type="button" onClick={gallery.reload}>
          Retry
        </button>
      </p>
    )
  }
  return <GalleryView token={token} gallery={gallery.state.value} />
}

function GalleryView({ token, gallery }: { token: string; gallery: PublicGallery }) {
  const [downloading, setDownloading] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone

  async function download(photo: PublicPhoto) {
    setDownloading(photo.id)
    setError(null)
    try {
      startDownload((await requestDownload(token, photo.id)).url)
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 404
          ? 'This link no longer works. Ask your photographer for a new one.'
          : `The download could not start${reference(e)}. Try again.`,
      )
    } finally {
      setDownloading(null)
    }
  }

  const downloadLabel = gallery.downloads === 'ORIGINALS' ? 'Download original' : 'Download'
  return (
    <section aria-labelledby="public-gallery-heading">
      <h1 id="public-gallery-heading">{gallery.title}</h1>
      <p>
        By {gallery.studioName} ·{' '}
        <span className="muted">This link works until {formatDate(gallery.linkExpiresAt, timezone)}.</span>
      </p>
      {error && <p role="alert">{error}</p>}
      {gallery.photos.length === 0 ? (
        <p className="muted">This gallery has no photos.</p>
      ) : (
        <ul className="photo-grid public-grid" aria-label="Photos">
          {gallery.photos.map((photo, index) => (
            <li key={photo.id}>
              <a href={photo.previewUrl} target="_blank" rel="noreferrer">
                <img src={photo.thumbnailUrl} alt={`Photo ${index + 1}`} loading="lazy" />
              </a>
              {gallery.downloads !== 'NONE' && (
                <button
                  type="button"
                  disabled={downloading !== null}
                  onClick={() => download(photo)}
                  aria-label={`${downloadLabel}: photo ${index + 1}`}
                >
                  {downloading === photo.id ? 'Starting…' : downloadLabel}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
