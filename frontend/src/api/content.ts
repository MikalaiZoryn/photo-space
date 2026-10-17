import type { PhotoAsset } from './assets.ts'
import { api, withQuery, type Page } from './client.ts'

/** Which provider writes drafts, and its limits. `mock` writes sample text locally and sends nothing anywhere. */
export type ContentSettings = {
  provider: string
  model: string
  maxPhotos: number
  maxCaptions: number
  promptVersion: string
}

export type BrandProfile = {
  voice: string | null
  audience: string | null
  preferences: string | null
  avoid: string | null
  /** 0 until the profile is first saved. */
  version: number
  updatedAt: string | null
}

export type BrandProfileInput = Pick<BrandProfile, 'voice' | 'audience' | 'preferences' | 'avoid'>

/** PENDING covers queued, generating, and waiting for an automatic retry. */
export type BatchStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED'
export type DraftStatus = 'DRAFT' | 'APPROVED'
export type DraftSource = 'GENERATED' | 'EDITED'

export type ContentBatch = {
  id: string
  sessionId: string
  sessionTitle: string
  status: BatchStatus
  sessionType: string | null
  notes: string | null
  captionCount: number
  includeHashtags: boolean
  includeCallToAction: boolean
  run: number
  /** Why it failed, or, while PENDING, the problem it is retrying after. */
  failureReason: string | null
  provider: string | null
  model: string | null
  promptVersion: string | null
  carouselRationale: string | null
  storyIdeas: string[]
  warnings: string[]
  photoCount: number
  draftCount: number
  approvedCount: number
  createdAt: string
  completedAt: string | null
}

export type ContentDraft = {
  id: string
  batchId: string
  position: number
  status: DraftStatus
  /** The current version; approval applies to `approvedVersion` only. */
  version: number
  caption: string
  callToAction: string | null
  /** Without the # sign. */
  hashtags: string[]
  source: DraftSource
  approvedVersion: number | null
  approvedAt: string | null
  updatedAt: string
  /** Review hints: details in the draft that are not in the notes or brand profile. */
  checks: string[]
}

export type DraftVersion = {
  version: number
  caption: string
  callToAction: string | null
  hashtags: string[]
  source: DraftSource
  createdAt: string
}

/** A batch with its photos in selection order (short-lived URLs), the suggested carousel order, and drafts. */
export type BatchDetail = { batch: ContentBatch; photos: PhotoAsset[]; carousel: string[]; drafts: ContentDraft[] }

export type DraftDetail = { draft: ContentDraft; versions: DraftVersion[] }

export type BatchInput = {
  sessionId: string
  assetIds: string[]
  notes: string | null
  captionCount: number
  includeHashtags: boolean
  includeCallToAction: boolean
}

export type DraftInput = { caption: string; callToAction: string | null; hashtags: string[] }

function contentPath(workspaceId: string) {
  return `/api/workspaces/${encodeURIComponent(workspaceId)}/content`
}

function batchPath(workspaceId: string, batchId: string) {
  return `${contentPath(workspaceId)}/batches/${encodeURIComponent(batchId)}`
}

function draftPath(workspaceId: string, draftId: string) {
  return `${contentPath(workspaceId)}/drafts/${encodeURIComponent(draftId)}`
}

export function getContentSettings(workspaceId: string, signal?: AbortSignal): Promise<ContentSettings> {
  return api<ContentSettings>(`${contentPath(workspaceId)}/settings`, { signal })
}

export function getBrandProfile(workspaceId: string, signal?: AbortSignal): Promise<BrandProfile> {
  return api<BrandProfile>(`${contentPath(workspaceId)}/brand-profile`, { signal })
}

export function updateBrandProfile(workspaceId: string, version: number, profile: BrandProfileInput): Promise<BrandProfile> {
  return api<BrandProfile>(`${contentPath(workspaceId)}/brand-profile`, { method: 'PUT', body: { version, profile } })
}

export function listBatches(
  workspaceId: string,
  query: { sessionId?: string; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<Page<ContentBatch>> {
  return api<Page<ContentBatch>>(withQuery(`${contentPath(workspaceId)}/batches`, query), { signal })
}

/** Queues generation. The batch is PENDING until the job finishes; poll it with `getBatch`. */
export function createBatch(workspaceId: string, input: BatchInput): Promise<BatchDetail> {
  return api<BatchDetail>(`${contentPath(workspaceId)}/batches`, { method: 'POST', body: input })
}

export function getBatch(workspaceId: string, batchId: string, signal?: AbortSignal): Promise<BatchDetail> {
  return api<BatchDetail>(batchPath(workspaceId, batchId), { signal })
}

export function retryBatch(workspaceId: string, batchId: string): Promise<BatchDetail> {
  return api<BatchDetail>(`${batchPath(workspaceId, batchId)}/retry`, { method: 'POST' })
}

/** A new batch with the same photos, notes, and options. */
export function regenerateBatch(workspaceId: string, batchId: string): Promise<BatchDetail> {
  return api<BatchDetail>(`${batchPath(workspaceId, batchId)}/regenerate`, { method: 'POST' })
}

export function getDraft(workspaceId: string, draftId: string, signal?: AbortSignal): Promise<DraftDetail> {
  return api<DraftDetail>(draftPath(workspaceId, draftId), { signal })
}

/** Saves an edit as a new version. An approved draft becomes a draft again. */
export function updateDraft(workspaceId: string, draftId: string, version: number, draft: DraftInput): Promise<ContentDraft> {
  return api<ContentDraft>(draftPath(workspaceId, draftId), { method: 'PUT', body: { version, draft } })
}

/** Approves exactly `version`, or takes the approval back. */
export function changeDraftStatus(
  workspaceId: string,
  draftId: string,
  version: number,
  status: DraftStatus,
): Promise<ContentDraft> {
  return api<ContentDraft>(`${draftPath(workspaceId, draftId)}/status`, { method: 'POST', body: { version, status } })
}

/** The post as it would be pasted into Instagram: caption, call to action, and hashtags. */
export function postText(draft: Pick<ContentDraft, 'caption' | 'callToAction' | 'hashtags'>): string {
  const parts = [draft.caption]
  if (draft.callToAction) parts.push(draft.callToAction)
  if (draft.hashtags.length > 0) parts.push(draft.hashtags.map((tag) => `#${tag}`).join(' '))
  return parts.join('\n\n')
}

/** Hashtags typed with spaces, commas, or # signs, as the API takes them. */
export function parseHashtags(text: string): string[] {
  return text
    .split(/[\s,]+/)
    .map((tag) => tag.replace(/^#+/, ''))
    .filter((tag) => tag.length > 0)
}
