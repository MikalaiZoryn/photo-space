import type { SessionStatus } from '../api/sessions.ts'

export const statusLabels: Record<SessionStatus, string> = {
  INQUIRY: 'Inquiry',
  BOOKED: 'Booked',
  COMPLETED: 'Completed',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
}

/** Button text for changing to a status. */
export const statusActions: Record<SessionStatus, string> = {
  INQUIRY: 'Reopen as inquiry',
  BOOKED: 'Mark booked',
  COMPLETED: 'Mark completed',
  DELIVERED: 'Mark delivered',
  CANCELLED: 'Cancel session',
}
