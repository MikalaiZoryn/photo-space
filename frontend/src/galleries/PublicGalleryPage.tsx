import { useState } from 'react'
import { ApiError } from '../api/client.ts'
import { fetchPublicGallery, requestDownload, type PublicGallery, type PublicPhoto } from '../api/galleries.ts'
import { formatDate } from '../time.ts'
import { startDownload } from '../ui/download.ts'
import { reference } from '../ui/formError.ts'
import { useLoad } from '../useLoad.ts'
import type { ReactNode } from 'react'
import { Download, LinkIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Brand } from '../layout/Brand.tsx'
import { EmptyState, ErrorAlert, Loading } from '../ui/Feedback.tsx'

/**
 * What a client sees after opening a share link: the one gallery the token opens, without signing in. The
 * token never leaves this page except in the request header the API expects.
 */
export function PublicGalleryPage({ token }: { token: string }) {
  const gallery = useLoad((signal) => fetchPublicGallery(token, signal), [token])

  let content
  if (gallery.state.kind === 'loading') content = <Loading>Loading gallery…</Loading>
  else if (gallery.state.kind === 'error') {
    const error = gallery.state.error
    content =
      error instanceof ApiError && error.status === 404 ? (
        <section
          aria-labelledby="gallery-unavailable-heading"
          className="mx-auto max-w-md space-y-3 rounded-2xl border bg-card p-8 text-center shadow-sm"
        >
          <span className="mx-auto flex size-12 items-center justify-center rounded-full bg-accent text-accent-foreground">
            <LinkIcon className="size-5" aria-hidden="true" />
          </span>
          <h1 id="gallery-unavailable-heading" className="text-xl font-semibold">
            This gallery link does not work
          </h1>
          <p className="text-sm text-muted-foreground">
            It may have expired or been revoked, or the gallery is no longer shared. Ask your photographer for a new link.
          </p>
        </section>
      ) : (
        <ErrorAlert>
          <span className="flex flex-wrap items-center gap-3">
            Could not load the gallery{reference(error)}.{' '}
            <Button type="button" variant="outline" size="sm" onClick={gallery.reload}>
              Retry
            </Button>
          </span>
        </ErrorAlert>
      )
  } else content = <GalleryView token={token} gallery={gallery.state.value} />

  return <PublicLayout>{content}</PublicLayout>
}

/** A visitor's page: no app navigation, just the gallery. */
function PublicLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col">
      <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6 sm:py-12 lg:px-8">{children}</main>
      <footer className="flex justify-center px-4 py-6 text-xs text-muted-foreground opacity-80">
        <span className="flex items-center gap-2">
          Shared with <Brand />
        </span>
      </footer>
    </div>
  )
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
      <header className="mb-8 space-y-3 text-center sm:mb-12">
        <p className="text-sm font-medium tracking-widest text-muted-foreground uppercase">By {gallery.studioName}</p>
        <h1 id="public-gallery-heading" className="text-3xl font-semibold sm:text-5xl">
          {gallery.title}
        </h1>
        <p className="text-sm text-muted-foreground">This link works until {formatDate(gallery.linkExpiresAt, timezone)}.</p>
      </header>
      {error && (
        <div className="mx-auto mb-6 max-w-2xl">
          <ErrorAlert>{error}</ErrorAlert>
        </div>
      )}
      {gallery.photos.length === 0 ? (
        <EmptyState>This gallery has no photos.</EmptyState>
      ) : (
        <ul className="grid grid-cols-1 gap-4 min-[420px]:grid-cols-2 md:grid-cols-3 xl:grid-cols-4" aria-label="Photos">
          {gallery.photos.map((photo, index) => (
            <li key={photo.id} className="group relative overflow-hidden rounded-xl bg-muted">
              <a href={photo.previewUrl} target="_blank" rel="noreferrer" className="block rounded-xl">
                <img
                  src={photo.thumbnailUrl}
                  alt={`Photo ${index + 1}`}
                  loading="lazy"
                  className="block aspect-[4/5] w-full object-cover transition-transform duration-500 group-hover:scale-[1.03]"
                />
              </a>
              {gallery.downloads !== 'NONE' && (
                <Button
                  type="button"
                  size="sm"
                  variant="secondary"
                  className="absolute right-2 bottom-2 bg-card/90 shadow-sm backdrop-blur hover:bg-card"
                  disabled={downloading !== null}
                  onClick={() => download(photo)}
                  aria-label={`${downloadLabel}: photo ${index + 1}`}
                >
                  <Download aria-hidden="true" />
                  {downloading === photo.id ? 'Starting…' : downloadLabel}
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
