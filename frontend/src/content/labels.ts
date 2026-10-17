import type { BatchStatus, DraftStatus } from '../api/content.ts'
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
