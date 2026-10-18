import type { BatchStatus, CaptionRule, DraftStatus, PostFormat, PostStage, PostStatus } from '../api/content.ts'
import type { Tone } from '../ui/StatusBadge.tsx'

export const batchStatusLabels: Record<BatchStatus, string> = {
  PENDING: 'Generating',
  SUCCEEDED: 'Ready',
  FAILED: 'Failed',
}

export const batchStatusTones: Record<BatchStatus, Tone> = {
  PENDING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'danger',
}

export const draftStatusLabels: Record<DraftStatus, string> = {
  DRAFT: 'Draft',
  APPROVED: 'Approved',
}

export const draftStatusTones: Record<DraftStatus, Tone> = {
  DRAFT: 'neutral',
  APPROVED: 'success',
}

export const stageLabels: Record<PostStage, string> = {
  SNEAK_PEEK: 'Sneak peek',
  DELIVERY: 'Delivery',
  DRIP: 'Later post',
}

export const formatLabels: Record<PostFormat, string> = {
  SINGLE: 'Photo',
  CAROUSEL: 'Carousel',
  REEL: 'Reel',
  STORY: 'Story',
}

export const postStatusLabels: Record<PostStatus, string> = {
  PROPOSED: 'To review',
  APPROVED: 'Approved',
  SCHEDULED: 'Scheduled',
  POSTED: 'Posted',
}

export const postStatusTones: Record<PostStatus, Tone> = {
  PROPOSED: 'warning',
  APPROVED: 'info',
  SCHEDULED: 'info',
  POSTED: 'success',
}

export const ruleTones: Record<CaptionRule['severity'], Tone> = {
  BLOCKER: 'danger',
  WARNING: 'warning',
  TIP: 'neutral',
}
