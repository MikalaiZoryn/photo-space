import type { InquiryStatus } from '../api/inquiries.ts'
import type { FormStatus, QuestionType } from '../api/inquiryForm.ts'
import type { Tone } from '../ui/StatusBadge.tsx'

export const inquiryStatusLabels: Record<InquiryStatus, string> = {
  RECEIVED: 'Checking',
  NEW: 'New',
  NEEDS_REVIEW: 'Possible match',
  ASSOCIATED: 'Linked',
  DISMISSED: 'Dismissed',
}

export const inquiryStatusTones: Record<InquiryStatus, Tone> = {
  RECEIVED: 'neutral',
  NEW: 'info',
  NEEDS_REVIEW: 'warning',
  ASSOCIATED: 'success',
  DISMISSED: 'neutral',
}

export const formStatusLabels: Record<FormStatus, string> = {
  DRAFT: 'Not published',
  PUBLISHED: 'Published',
  UNPUBLISHED: 'Unpublished',
}

export const formStatusTones: Record<FormStatus, Tone> = {
  DRAFT: 'neutral',
  PUBLISHED: 'success',
  UNPUBLISHED: 'warning',
}

export const questionTypeLabels: Record<QuestionType, string> = {
  SHORT_TEXT: 'Short text',
  LONG_TEXT: 'Long text',
  EMAIL: 'Email',
  PHONE: 'Phone',
  DATE: 'Date',
  SINGLE_CHOICE: 'Single choice',
}
