import { api } from './client.ts'
import type { PublicBranding } from './branding.ts'

export const QUESTION_TYPES = ['SHORT_TEXT', 'LONG_TEXT', 'EMAIL', 'PHONE', 'DATE', 'SINGLE_CHOICE'] as const
export type QuestionType = (typeof QUESTION_TYPES)[number]

export type Question = {
  /** Stable: answers are stored by it. */
  id: string
  type: QuestionType
  label: string
  help: string | null
  required: boolean
  /** Only for SINGLE_CHOICE. */
  choices: string[]
}

export type ServiceSelector = { label: string; required: boolean; choices: string[] }

export type FormConfig = {
  title: string
  intro: string | null
  confirmation: string
  questions: Question[]
  service: ServiceSelector | null
}

/**
 * Question IDs the server reads, with the type each must have. "name" and "email" are required in every form;
 * "phone" and "preferredDate" are optional.
 */
export const RESERVED_QUESTIONS: Record<string, QuestionType> = {
  name: 'SHORT_TEXT',
  email: 'EMAIL',
  phone: 'PHONE',
  preferredDate: 'DATE',
}
export const REQUIRED_QUESTIONS = ['name', 'email']

export type FormStatus = 'DRAFT' | 'PUBLISHED' | 'UNPUBLISHED'

export type InquiryForm = {
  id: string
  status: FormStatus
  slug: string
  draft: FormConfig
  /** The version visitors see while PUBLISHED; the last one published otherwise. */
  published: { id: string; number: number; config: FormConfig; publishedAt: string } | null
  unpublishedChanges: boolean
  version: number
  createdAt: string
  updatedAt: string
}

function formPath(workspaceId: string) {
  return `/api/workspaces/${encodeURIComponent(workspaceId)}/inquiry-form`
}

/** 404 until the form is created. */
export function getInquiryForm(workspaceId: string, signal?: AbortSignal): Promise<InquiryForm> {
  return api<InquiryForm>(formPath(workspaceId), { signal })
}

/** Creates the form from the default template, or returns the existing one. */
export function createInquiryForm(workspaceId: string): Promise<InquiryForm> {
  return api<InquiryForm>(formPath(workspaceId), { method: 'POST' })
}

export function saveDraft(workspaceId: string, version: number, draft: FormConfig): Promise<InquiryForm> {
  return api<InquiryForm>(formPath(workspaceId), { method: 'PUT', body: { version, draft } })
}

export function publishForm(workspaceId: string, version: number): Promise<InquiryForm> {
  return api<InquiryForm>(`${formPath(workspaceId)}/publish`, { method: 'POST', body: { version } })
}

export function unpublishForm(workspaceId: string): Promise<InquiryForm> {
  return api<InquiryForm>(`${formPath(workspaceId)}/unpublish`, { method: 'POST' })
}

/** The hosted page and the embeddable page of a form. */
export function hostedUrl(slug: string, origin = window.location.origin): string {
  return `${origin}/inquiry/${encodeURIComponent(slug)}`
}

export function embedUrl(slug: string, origin = window.location.origin): string {
  return `${origin}/embed/inquiry/${encodeURIComponent(slug)}`
}

/** The iframe a photographer pastes into their own site. Its height is fixed: there is no resizing script. */
export function embedSnippet(slug: string, origin = window.location.origin): string {
  return `<iframe src="${embedUrl(slug, origin)}" title="Inquiry form" width="100%" height="1100" style="border:0;max-width:640px" loading="lazy"></iframe>`
}

// --- The public pages: no sign-in, no cookies, no CSRF token. ---

export type PublicForm = { form: FormConfig; branding: PublicBranding }

export type Submission = {
  idempotencyKey: string
  answers: Record<string, string>
  service: string | null
  source: 'HOSTED' | 'EMBED'
  referrer: string | null
  utm: { source: string | null; medium: string | null; campaign: string | null; term: string | null; content: string | null }
  /** The hidden honeypot field; people leave it empty. */
  website: string
  elapsedMillis: number
}

function publicPath(slug: string) {
  return `/api/public/inquiry/${encodeURIComponent(slug)}`
}

export function fetchPublicForm(slug: string, signal?: AbortSignal): Promise<PublicForm> {
  return api<PublicForm>(publicPath(slug), { signal, csrf: false })
}

export function submitInquiry(slug: string, submission: Submission): Promise<{ received: boolean }> {
  return api<{ received: boolean }>(publicPath(slug), { method: 'POST', body: submission, csrf: false })
}
