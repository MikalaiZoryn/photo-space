import type { GalleryDownloads, GalleryStatus, LinkState } from '../api/galleries.ts'

export const downloadLabels: Record<GalleryDownloads, string> = {
  NONE: 'No downloads (view only)',
  PREVIEWS: 'Web-size downloads (2048 px JPEG)',
  ORIGINALS: 'Full-resolution originals',
}

export const galleryStatusLabels: Record<GalleryStatus, string> = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
}

export const linkStateLabels: Record<LinkState, string> = {
  ACTIVE: 'Active',
  EXPIRED: 'Expired',
  REVOKED: 'Revoked',
}
