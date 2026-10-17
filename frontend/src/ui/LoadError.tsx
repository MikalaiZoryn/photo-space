import { ApiError } from '../api/client.ts'
import { Button } from '@/components/ui/button'
import { ErrorAlert } from './Feedback.tsx'
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
    return <ErrorAlert>This {what} was not found. It may belong to another workspace or never have existed.</ErrorAlert>
  }
  return (
    <ErrorAlert>
      <span className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <span>
          Could not load {what}
          {reference(error)}.
        </span>{' '}
        <Button type="button" variant="outline" size="sm" onClick={onRetry}>
          Retry
        </Button>
      </span>
    </ErrorAlert>
  )
}
