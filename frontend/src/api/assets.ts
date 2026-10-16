import { api, withQuery, type Page } from './client.ts'

export const ASSET_STATUSES = ['PENDING_UPLOAD', 'PROCESSING', 'READY', 'REJECTED', 'FAILED'] as const
export type AssetStatus = (typeof ASSET_STATUSES)[number]

export type PhotoAsset = {
  id: string
  sessionId: string
  status: AssetStatus
  originalFilename: string
  /** The type detected from the file once processed; the declared type until then. */
  contentType: string
  byteSize: number
  /** Upright dimensions, once processed. */
  width: number | null
  height: number | null
  checksumSha256: string | null
  /** Why a REJECTED or FAILED photo could not be used. */
  failureReason: string | null
  /** Short-lived URLs, only when READY. Never store or log them. */
  thumbnailUrl: string | null
  previewUrl: string | null
  uploadedAt: string | null
  processedAt: string | null
  createdAt: string
}

/** Where to send the file: a short-lived presigned URL and the headers it was signed with. */
export type UploadTarget = {
  url: string
  method: 'PUT'
  headers: Record<string, string>
  expiresAt: string
}

export type StartedUpload = { asset: PhotoAsset; upload: UploadTarget }

/** The formats and size the server accepts. It checks again, and verifies the file's actual content. */
export const ACCEPTED_TYPES = ['image/jpeg', 'image/png']
export const MAX_UPLOAD_BYTES = 100 * 1024 * 1024

/** The upload to storage itself failed (not an API error). */
export class StorageUploadError extends Error {
  readonly status: number

  constructor(status: number) {
    super(`Storage upload failed (HTTP ${status})`)
    this.name = 'StorageUploadError'
    this.status = status
  }
}

function workspacePath(workspaceId: string) {
  return `/api/workspaces/${encodeURIComponent(workspaceId)}`
}

function sessionAssetsPath(workspaceId: string, sessionId: string) {
  return `${workspacePath(workspaceId)}/sessions/${encodeURIComponent(sessionId)}/assets`
}

export function listSessionAssets(
  workspaceId: string,
  sessionId: string,
  query: { page?: number; size?: number },
  signal?: AbortSignal,
): Promise<Page<PhotoAsset>> {
  return api<Page<PhotoAsset>>(withQuery(sessionAssetsPath(workspaceId, sessionId), query), { signal })
}

/**
 * Uploads one photo: asks the API for an upload URL, sends the file straight to private storage, then tells
 * the API the upload finished so processing starts. Returns the asset as completion left it (PROCESSING).
 */
export async function uploadPhoto(workspaceId: string, sessionId: string, file: File): Promise<PhotoAsset> {
  const started = await api<StartedUpload>(sessionAssetsPath(workspaceId, sessionId), {
    method: 'POST',
    body: { filename: file.name, contentType: file.type, byteSize: file.size },
  })
  // Cross-origin, so no cookies or CSRF token go to storage; only the signed headers.
  const response = await fetch(started.upload.url, {
    method: started.upload.method,
    headers: started.upload.headers,
    body: file,
  })
  if (!response.ok) throw new StorageUploadError(response.status)
  return api<PhotoAsset>(`${workspacePath(workspaceId)}/assets/${encodeURIComponent(started.asset.id)}/complete`, {
    method: 'POST',
  })
}
