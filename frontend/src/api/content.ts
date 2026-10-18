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

/** CAPTIONS: caption options for chosen photos. PLAN: the posts of a session plan. */
export type BatchKind = 'CAPTIONS' | 'PLAN'

export type ContentBatch = {
  id: string
  sessionId: string
  sessionTitle: string
  kind: BatchKind
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
  /** Caption rules the draft breaks, most serious first. Only a BLOCKER stops a save. */
  rules: CaptionRule[]
}

export type CaptionRule = {
  code: 'HASHTAG_LIMIT' | 'SESSION_TYPE' | 'LOCATION' | 'CALL_TO_ACTION' | 'SEND_PROMPT'
  severity: 'BLOCKER' | 'WARNING' | 'TIP'
  message: string
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
  /** `toReview` keeps only batches with drafts not approved yet. */
  query: { sessionId?: string; toReview?: boolean; page?: number; size?: number },
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

// --- Content Studio (Milestone 13): settings, session plans, and planned posts. ---

/** Instagram's cap on hashtags per post and Reel. */
export const MAX_HASHTAGS = 5

/** Content Studio settings that persist across sessions. Version 0 means the defaults, never saved. */
export type StudioSettings = {
  weeklyGoal: number
  autoPlans: boolean
  sneakPeekDays: number
  dripPosts: number
  dripSpacingDays: number
  studioArea: string | null
  version: number
  updatedAt: string | null
}

export type StudioSettingsInput = Omit<StudioSettings, 'version' | 'updatedAt'>

export type PostStage = 'SNEAK_PEEK' | 'DELIVERY' | 'DRIP'
/** SINGLE, CAROUSEL, and REEL count toward the weekly goal; a STORY does not. */
export type PostFormat = 'SINGLE' | 'CAROUSEL' | 'REEL' | 'STORY'
/** Derived: PROPOSED until approved, then APPROVED (no day) or SCHEDULED, and POSTED once marked. */
export type PostStatus = 'PROPOSED' | 'APPROVED' | 'SCHEDULED' | 'POSTED'

export type Plan = {
  id: string
  sessionId: string
  sessionTitle: string
  origin: 'AUTOMATIC' | 'MANUAL'
  batchId: string
  /** Of its latest generation. */
  status: BatchStatus
  failureReason: string | null
  warnings: string[]
  provider: string | null
  model: string | null
  /** The day the session's gallery was first published; delivery and drip dates count from it. */
  publishedOn: string | null
  postCount: number
  proposedCount: number
  approvedCount: number
  postedCount: number
  createdAt: string
  updatedAt: string
}

export type Post = {
  id: string
  planId: string
  sessionId: string
  sessionTitle: string
  draftId: string
  stage: PostStage
  format: PostFormat
  status: PostStatus
  /** Notes for a Reel or Story. */
  idea: string | null
  offsetDays: number
  /** Calendar dates ("2026-11-07") in the workspace's days. */
  plannedOn: string | null
  postedOn: string | null
  postUrl: string | null
  /** A one-post caption rewrite is running. */
  regenerating: boolean
  failureReason: string | null
  version: number
  draftStatus: DraftStatus
  draftVersion: number
  caption: string
  callToAction: string | null
  hashtags: string[]
  photoCount: number
  /** Short-lived. */
  coverThumbnailUrl: string | null
  updatedAt: string
}

/** A 4:5 crop as fractions of the upright photo. */
export type Crop = { x: number; y: number; width: number; height: number }

export type PostPhoto = { photo: PhotoAsset; crop: Crop; customCrop: boolean }

export type PlanDetail = { plan: Plan; posts: Post[] }

export type PostDetail = { post: Post; photos: PostPhoto[]; draft: ContentDraft }

/** Download links expire after a few minutes. */
export type KitPhoto = { assetId: string; filename: string; thumbnailUrl: string; exportUrl: string; originalUrl: string | null }

export type PostKit = { post: Post; photos: KitPhoto[] }

export type PostInput = {
  stage: PostStage
  format: PostFormat
  photos: string[]
  draft: DraftInput
  idea: string | null
  plannedOn: string | null
}

export function getStudioSettings(workspaceId: string, signal?: AbortSignal): Promise<StudioSettings> {
  return api<StudioSettings>(`${contentPath(workspaceId)}/studio-settings`, { signal })
}

export function updateStudioSettings(workspaceId: string, version: number, settings: StudioSettingsInput): Promise<StudioSettings> {
  return api<StudioSettings>(`${contentPath(workspaceId)}/studio-settings`, { method: 'PUT', body: { version, settings } })
}

function planPath(workspaceId: string, sessionId: string) {
  return `${contentPath(workspaceId)}/sessions/${encodeURIComponent(sessionId)}/plan`
}

function postPath(workspaceId: string, postId: string) {
  return `${contentPath(workspaceId)}/posts/${encodeURIComponent(postId)}`
}

/** The session's plan; rejects with a 404 ApiError when it has none. */
export function getPlan(workspaceId: string, sessionId: string, signal?: AbortSignal): Promise<PlanDetail> {
  return api<PlanDetail>(planPath(workspaceId, sessionId), { signal })
}

/** Creates the plan or refreshes it. It is PENDING until the job finishes; poll it with `getPlan`. */
export function requestPlan(workspaceId: string, sessionId: string): Promise<PlanDetail> {
  return api<PlanDetail>(planPath(workspaceId, sessionId), { method: 'POST' })
}

export function retryPlan(workspaceId: string, planId: string): Promise<PlanDetail> {
  return api<PlanDetail>(`${contentPath(workspaceId)}/plans/${encodeURIComponent(planId)}/retry`, { method: 'POST' })
}

export function listPlans(
  workspaceId: string,
  /** `toReview` keeps plans with posts to approve, or still generating or failed. */
  query: { toReview?: boolean; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<Page<Plan>> {
  return api<Page<Plan>>(withQuery(`${contentPath(workspaceId)}/plans`, query), { signal })
}

export function listPosts(
  workspaceId: string,
  /** `from`/`to` are inclusive calendar dates at most 62 days apart; `unscheduled` keeps approved posts without a day. */
  query: {
    sessionId?: string
    from?: string
    to?: string
    status?: PostStatus
    unscheduled?: boolean
    page?: number
    size?: number
  },
  signal?: AbortSignal,
): Promise<Page<Post>> {
  return api<Page<Post>>(withQuery(`${contentPath(workspaceId)}/posts`, query), { signal })
}

export function getPost(workspaceId: string, postId: string, signal?: AbortSignal): Promise<PostDetail> {
  return api<PostDetail>(postPath(workspaceId, postId), { signal })
}

export function addPost(workspaceId: string, planId: string, input: PostInput): Promise<PostDetail> {
  return api<PostDetail>(`${contentPath(workspaceId)}/plans/${encodeURIComponent(planId)}/posts`, {
    method: 'POST',
    body: input,
  })
}

/** Sets or clears the day to post. */
export function schedulePost(workspaceId: string, postId: string, version: number, plannedOn: string | null): Promise<Post> {
  return api<Post>(`${postPath(workspaceId, postId)}/schedule`, { method: 'PUT', body: { version, plannedOn } })
}

/** Replaces the photos in posting order. An approved post goes back to review. */
export function updatePostPhotos(
  workspaceId: string,
  postId: string,
  version: number,
  photos: { assetId: string; crop: Crop | null }[],
): Promise<PostDetail> {
  return api<PostDetail>(`${postPath(workspaceId, postId)}/photos`, { method: 'PUT', body: { version, photos } })
}

/** Records that the post was posted by hand, or takes that back. Nothing is posted to Instagram. */
export function markPosted(
  workspaceId: string,
  postId: string,
  version: number,
  posted: { postedOn: string; postUrl: string | null } | null,
): Promise<Post> {
  return api<Post>(`${postPath(workspaceId, postId)}/posted`, {
    method: 'POST',
    body: posted ? { version, posted: true, ...posted } : { version, posted: false },
  })
}

/** Queues a new caption for this post alone; poll the post while `regenerating`. */
export function regeneratePost(workspaceId: string, postId: string, version: number): Promise<Post> {
  return api<Post>(`${postPath(workspaceId, postId)}/regenerate`, { method: 'POST', body: { version } })
}

export function getPostKit(workspaceId: string, postId: string, signal?: AbortSignal): Promise<PostKit> {
  return api<PostKit>(`${postPath(workspaceId, postId)}/kit`, { signal })
}

export function deletePost(workspaceId: string, postId: string, version: number): Promise<void> {
  return api<void>(withQuery(postPath(workspaceId, postId), { version }), { method: 'DELETE' })
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
