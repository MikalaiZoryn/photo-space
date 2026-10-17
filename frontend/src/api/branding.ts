import { api } from './client.ts'
import { StorageUploadError, type AssetStatus, type PhotoAsset, type StartedUpload } from './assets.ts'

export const BRAND_FONTS = ['INTER', 'SYSTEM', 'SERIF', 'ROUNDED'] as const
export type BrandFont = (typeof BRAND_FONTS)[number]

/** A logo's processing state; `url` is short-lived and set only once READY. */
export type Logo = { id: string; status: AssetStatus; url: string | null }

export type Branding = {
  /** Null when visitors see `workspaceName` instead. */
  displayName: string | null
  workspaceName: string
  logo: Logo | null
  primaryColor: string
  accentColor: string
  font: BrandFont
  email: string | null
  phone: string | null
  description: string | null
  version: number
  updatedAt: string | null
}

export type BrandingInput = {
  displayName: string | null
  logoAssetId: string | null
  primaryColor: string
  accentColor: string
  font: BrandFont
  email: string | null
  phone: string | null
  description: string | null
}

/** Branding as visitors of the public pages see it, with defaults filled in. */
export type PublicBranding = {
  displayName: string
  logoUrl: string | null
  primaryColor: string
  accentColor: string
  font: BrandFont
  email: string | null
  phone: string | null
  description: string | null
}

export const MAX_LOGO_BYTES = 5 * 1024 * 1024

function workspacePath(workspaceId: string) {
  return `/api/workspaces/${encodeURIComponent(workspaceId)}`
}

export function getBranding(workspaceId: string, signal?: AbortSignal): Promise<Branding> {
  return api<Branding>(`${workspacePath(workspaceId)}/branding`, { signal })
}

/** Saves only if nobody changed the branding since `version` was read; otherwise the server returns 409. */
export function updateBranding(workspaceId: string, version: number, branding: BrandingInput): Promise<Branding> {
  return api<Branding>(`${workspacePath(workspaceId)}/branding`, { method: 'PUT', body: { version, branding } })
}

/**
 * Uploads a logo like a photo: an upload URL from the API, the file straight to private storage, then
 * completion, which starts processing. Returns the asset as completion left it (PROCESSING).
 */
export async function uploadLogo(workspaceId: string, file: File): Promise<PhotoAsset> {
  const started = await api<StartedUpload>(`${workspacePath(workspaceId)}/branding/logo-uploads`, {
    method: 'POST',
    body: { filename: file.name, contentType: file.type, byteSize: file.size },
  })
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

export function getAsset(workspaceId: string, assetId: string, signal?: AbortSignal): Promise<PhotoAsset> {
  return api<PhotoAsset>(`${workspacePath(workspaceId)}/assets/${encodeURIComponent(assetId)}`, { signal })
}

/** What visitors would see, for previews inside the app. */
export function publicView(branding: Branding, logoUrl = branding.logo?.url ?? null): PublicBranding {
  return {
    displayName: branding.displayName ?? branding.workspaceName,
    logoUrl,
    primaryColor: branding.primaryColor,
    accentColor: branding.accentColor,
    font: branding.font,
    email: branding.email,
    phone: branding.phone,
    description: branding.description,
  }
}
