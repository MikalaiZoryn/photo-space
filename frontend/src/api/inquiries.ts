import { api, withQuery, type Page } from './client.ts'
import type { ClientSummary } from './clients.ts'
import type { QuestionType } from './inquiryForm.ts'
import type { SessionSummary } from './tasks.ts'

export const INQUIRY_STATUSES = ['RECEIVED', 'NEW', 'NEEDS_REVIEW', 'ASSOCIATED', 'DISMISSED'] as const
export type InquiryStatus = (typeof INQUIRY_STATUSES)[number]

export type InquirySummary = {
  id: string
  status: InquiryStatus
  name: string
  email: string
  service: string | null
  preferredDate: string | null
  source: 'HOSTED' | 'EMBED'
  submittedAt: string
}

export type ClientMatch = { id: string; name: string; email: string; phone: string | null }

export type Inquiry = InquirySummary & {
  phone: string | null
  answers: { questionId: string; label: string; type: QuestionType; value: string }[]
  formVersion: number
  referrer: string | null
  utm: { source: string | null; medium: string | null; campaign: string | null; term: string | null; content: string | null }
  /** Active clients with the same email, while the inquiry is open. */
  matches: ClientMatch[]
  client: ClientSummary | null
  clientCreated: boolean
  session: SessionSummary | null
  reviewTaskId: string | null
  followUpTaskId: string | null
  associatedAt: string | null
  dismissedAt: string | null
  version: number
  updatedAt: string
}

function inquiriesPath(workspaceId: string) {
  return `/api/workspaces/${encodeURIComponent(workspaceId)}/inquiries`
}

function inquiryPath(workspaceId: string, inquiryId: string) {
  return `${inquiriesPath(workspaceId)}/${encodeURIComponent(inquiryId)}`
}

/** Without a status, every inquiry except dismissed ones. */
export function listInquiries(
  workspaceId: string,
  query: { status?: InquiryStatus; page?: number },
  signal?: AbortSignal,
): Promise<Page<InquirySummary>> {
  return api<Page<InquirySummary>>(withQuery(inquiriesPath(workspaceId), query), { signal })
}

export function getInquiry(workspaceId: string, inquiryId: string, signal?: AbortSignal): Promise<Inquiry> {
  return api<Inquiry>(inquiryPath(workspaceId, inquiryId), { signal })
}

/** Links an existing client, or creates one from the answers when `clientId` is null. */
export function associateInquiry(
  workspaceId: string,
  inquiryId: string,
  version: number,
  clientId: string | null,
): Promise<Inquiry> {
  return api<Inquiry>(`${inquiryPath(workspaceId, inquiryId)}/associate`, {
    method: 'POST',
    body: clientId ? { version, clientId } : { version, createClient: true },
  })
}

export function dismissInquiry(workspaceId: string, inquiryId: string, version: number): Promise<Inquiry> {
  return api<Inquiry>(`${inquiryPath(workspaceId, inquiryId)}/dismiss`, { method: 'POST', body: { version } })
}

export function reopenInquiry(workspaceId: string, inquiryId: string, version: number): Promise<Inquiry> {
  return api<Inquiry>(`${inquiryPath(workspaceId, inquiryId)}/reopen`, { method: 'POST', body: { version } })
}
