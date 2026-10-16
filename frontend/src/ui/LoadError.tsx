import { ApiError } from '../api/client.ts'
import { reference } from './formError.ts'

type Props = {
  error: unknown
  /** What failed to load, e.g. "clients". */
  what: string
  onRetry: () => void
}

/** A failed load: "not found" for a 404 (unknown, foreign, or malformed ID), otherwise a retryable error. */
export function LoadError({ error, what, onRetry }: Props) {
  if (error instanceof ApiError && error.status === 404) {
    return <p role="alert">This {what} was not found. It may belong to another workspace or never have existed.</p>
  }
  return (
    <p role="alert">
      Could not load {what}
      {reference(error)}.{' '}
      <button type="button" onClick={onRetry}>
        Retry
      </button>
    </p>
  )
}
