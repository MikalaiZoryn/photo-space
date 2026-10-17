import type { ReactNode } from 'react'
import { CircleAlert, ImageOff, LoaderCircle } from 'lucide-react'
import { Alert, AlertDescription } from '@/components/ui/alert'

/** A loading message, announced to screen readers. */
export function Loading({ children }: { children: ReactNode }) {
  return (
    <p role="status" className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
      <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />
      {children}
    </p>
  )
}

/** A failed action or load, with optional controls such as a retry button. */
export function ErrorAlert({ children }: { children: ReactNode }) {
  return (
    <Alert variant="destructive" className="border-tone-danger/30 bg-tone-danger-bg">
      <CircleAlert aria-hidden="true" />
      <AlertDescription className="text-tone-danger">{children}</AlertDescription>
    </Alert>
  )
}

/** A centered "nothing here yet" message. */
export function EmptyState({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed bg-card/50 px-6 py-12 text-center">
      <span className="flex size-10 items-center justify-center rounded-full bg-accent text-accent-foreground">
        <ImageOff className="size-5" aria-hidden="true" />
      </span>
      <p className="max-w-sm text-sm text-muted-foreground">{children}</p>
      {action}
    </div>
  )
}

/** The responsive grid shared by every photo list. */
export const photoGrid = 'grid grid-cols-2 gap-3 sm:grid-cols-3 sm:gap-4 lg:grid-cols-4 xl:grid-cols-5'
