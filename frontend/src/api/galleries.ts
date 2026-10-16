import type { PhotoAsset } from './assets.ts'
import { api, withQuery, type Page } from './client.ts'

export const GALLERY_DOWNLOADS = ['NONE', 'PREVIEWS', 'ORIGINALS'] as const
export type GalleryDownloads = (typeof GALLERY_DOWNLOADS)[number]
export type GalleryStatus = 'DRAFT' | 'PUBLISHED'

export type Gallery = {
  id: string
  sessionId: string
  title: string
  /** Share links open the gallery only while it is PUBLISHED and not archived. */
  status: GalleryStatus
  downloads: GalleryDownloads
  photoCount: number
  archivedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

/** A gallery with its photos in display order. Photo URLs are short-lived; never store or log them. */
export type GalleryDetail = { gallery: Gallery; photos: PhotoAsset[] }

export type GalleryInput = { title: string; downloads: GalleryDownloads }

export type LinkState = 'ACTIVE' | 'EXPIRED' | 'REVOKED'

/** A share link as photographers see it. Its token is not stored, so it is never part of this. */
export type ShareLink = {
  id: string
  state: LinkState
  expiresAt: string
  revokedAt: string | null
  createdAt: string
  views: number
  downloads: number
  lastAccessedAt: string | null
}

/** A new link and its token. The token is shown once and is a credential: never store or log it. */
export type CreatedLink = { link: ShareLink; token: string }

export type GalleryQuery = { sessionId?: string; archived?: boolean; page?: number; size?: number }

function galleriesPath(workspaceId: string) {
  return `/api/workspaces/${encodeURIComponent(workspaceId)}/galleries`
}

function galleryPath(workspaceId: string, galleryId: string) {
  return `${galleriesPath(workspaceId)}/${encodeURIComponent(galleryId)}`
}

export function listGalleries(workspaceId: string, query: GalleryQuery, signal?: AbortSignal): Promise<Page<Gallery>> {
  return api<Page<Gallery>>(withQuery(galleriesPath(workspaceId), query), { signal })
}

/** New galleries start as empty drafts. */
export function createGallery(workspaceId: string, sessionId: string, gallery: GalleryInput): Promise<GalleryDetail> {
  return api<GalleryDetail>(galleriesPath(workspaceId), { method: 'POST', body: { sessionId, gallery } })
}

export function getGallery(workspaceId: string, galleryId: string, signal?: AbortSignal): Promise<GalleryDetail> {
  return api<GalleryDetail>(galleryPath(workspaceId, galleryId), { signal })
}

export function updateGallery(
  workspaceId: string,
  galleryId: string,
  version: number,
  gallery: GalleryInput,
): Promise<GalleryDetail> {
  return api<GalleryDetail>(galleryPath(workspaceId, galleryId), { method: 'PUT', body: { version, gallery } })
}

/** Replaces all of the gallery's photos with `assetIds`, in that order. */
export function setGalleryPhotos(
  workspaceId: string,
  galleryId: string,
  version: number,
  assetIds: string[],
): Promise<GalleryDetail> {
  return api<GalleryDetail>(`${galleryPath(workspaceId, galleryId)}/photos`, {
    method: 'PUT',
    body: { version, assetIds },
  })
}

export function changeGalleryStatus(workspaceId: string, galleryId: string, status: GalleryStatus): Promise<GalleryDetail> {
  return api<GalleryDetail>(`${galleryPath(workspaceId, galleryId)}/status`, { method: 'POST', body: { status } })
}

export function setGalleryArchived(workspaceId: string, galleryId: string, archived: boolean): Promise<GalleryDetail> {
  return api<GalleryDetail>(`${galleryPath(workspaceId, galleryId)}/${archived ? 'archive' : 'restore'}`, {
    method: 'POST',
  })
}

export function listLinks(
  workspaceId: string,
  galleryId: string,
  query: { page?: number; size?: number },
  signal?: AbortSignal,
): Promise<Page<ShareLink>> {
  return api<Page<ShareLink>>(withQuery(`${galleryPath(workspaceId, galleryId)}/links`, query), { signal })
}

export function createLink(workspaceId: string, galleryId: string, validDays: number): Promise<CreatedLink> {
  return api<CreatedLink>(`${galleryPath(workspaceId, galleryId)}/links`, { method: 'POST', body: { validDays } })
}

export function revokeLink(workspaceId: string, galleryId: string, linkId: string): Promise<ShareLink> {
  return api<ShareLink>(`${galleryPath(workspaceId, galleryId)}/links/${encodeURIComponent(linkId)}/revoke`, {
    method: 'POST',
  })
}

// --- Gallery visitors: no sign-in, only the share token, sent in a header and never in a URL. ---

const TOKEN_HEADER = 'X-Gallery-Token'

export type PublicPhoto = {
  id: string
  width: number | null
  height: number | null
  /** Short-lived URLs; never store or log them. */
  thumbnailUrl: string
  previewUrl: string
}

export type PublicGallery = {
  title: string
  studioName: string
  downloads: GalleryDownloads
  linkExpiresAt: string
  photos: PublicPhoto[]
}

export function fetchPublicGallery(token: string, signal?: AbortSignal): Promise<PublicGallery> {
  return api<PublicGallery>('/api/public/gallery', { signal, headers: { [TOKEN_HEADER]: token } })
}

/** A short-lived URL that downloads one photo as a file, as the gallery's download setting allows. */
export function requestDownload(token: string, photoId: string): Promise<{ url: string; expiresAt: string }> {
  return api(`/api/public/gallery/photos/${encodeURIComponent(photoId)}/download`, {
    method: 'POST',
    headers: { [TOKEN_HEADER]: token },
  })
}
