import type { AuditEvent } from '../api/audit.ts'
import { paths } from '../routing.ts'

export const actionLabels: Record<string, string> = {
  SESSION_STATUS_CHANGED: 'Changed a session’s status',
  TASK_CREATED: 'Created a task',
  TASK_UPDATED: 'Edited a task',
  TASK_COMPLETED: 'Completed a task',
  TASK_REOPENED: 'Reopened a task',
  TASK_DELETED: 'Deleted a task',
  AUTOMATION_RULE_CREATED: 'Created an automation rule',
  AUTOMATION_RULE_UPDATED: 'Changed an automation rule',
  AUTOMATION_RUN_SUCCEEDED: 'An automation run created its task',
  AUTOMATION_RUN_FAILED: 'An automation run failed',
  AUTOMATION_RUN_RETRIED: 'Retried an automation run',
  GALLERY_PUBLISHED: 'Published a gallery',
  GALLERY_UNPUBLISHED: 'Unpublished a gallery',
  SHARE_LINK_CREATED: 'Created a gallery share link',
  SHARE_LINK_REVOKED: 'Revoked a gallery share link',
  CONTENT_DRAFT_APPROVED: 'Approved a content draft',
  CONTENT_DRAFT_UNAPPROVED: 'Withdrew a content draft approval',
  BRANDING_UPDATED: 'Changed the branding',
  INQUIRY_FORM_PUBLISHED: 'Published the inquiry form',
  INQUIRY_FORM_UNPUBLISHED: 'Unpublished the inquiry form',
  INQUIRY_MATCHED: 'Checked an inquiry for matching clients',
  INQUIRY_ASSOCIATED: 'Linked an inquiry to a client',
  INQUIRY_DISMISSED: 'Dismissed an inquiry',
  INQUIRY_REOPENED: 'Reopened an inquiry',
  INVITATION_CREATED: 'Invited someone to the workspace',
  INVITATION_RESENT: 'Resent an invitation',
  INVITATION_REVOKED: 'Revoked an invitation',
  INVITATION_ACCEPTED: 'Joined the workspace from an invitation',
}

/** Where an event's record can be opened, if it still has a page of its own. */
export function targetHref(event: AuditEvent): string | null {
  switch (event.targetType) {
    case 'SESSION':
      return paths.session(event.targetId)
    case 'TASK':
      return event.action === 'TASK_DELETED' ? null : paths.taskEdit(event.targetId)
    case 'GALLERY':
      return paths.gallery(event.targetId)
    case 'AUTOMATION_RULE':
    case 'AUTOMATION_RUN':
      return paths.automation()
    case 'INQUIRY':
      return paths.inquiry(event.targetId)
    case 'INQUIRY_FORM':
      return paths.inquiryForm()
    case 'BRANDING':
      return paths.branding()
    case 'INVITATION':
      return paths.members()
    default:
      return null
  }
}
