import { ApiError } from '../api/client.ts'

/** Field messages keyed by the input's own field name, plus a form-level message. */
export type FormError = { fields: Record<string, string>; message?: string }

export const noFormError: FormError = { fields: {} }

/**
 * Turns a failed save into something a form can show: per-field validation messages (400), the server's
 * explanation of a conflict such as a stale edit (409), or a generic message with the correlation ID.
 */
export function toFormError(error: unknown, action: string): FormError {
  if (error instanceof ApiError && error.status === 400 && error.problem.errors) {
    const fields: Record<string, string> = {}
    for (const [key, message] of Object.entries(error.problem.errors)) {
      // Update requests nest the fields ("client.name"); the form only knows "name".
      fields[key.slice(key.lastIndexOf('.') + 1)] = message
    }
    return { fields }
  }
  if (error instanceof ApiError && error.status === 409 && error.problem.detail) {
    return { fields: {}, message: error.problem.detail }
  }
  return { fields: {}, message: `Could not ${action}${reference(error)}.` }
}

/** " (ref …)" with the correlation ID of a failed call, so a user can quote it in a bug report. */
export function reference(error: unknown): string {
  return error instanceof ApiError && error.problem.correlationId ? ` (ref ${error.problem.correlationId})` : ''
}
