import type { ReactNode } from 'react'
import { cn } from 'cn'

type Props = {
  label: ReactNode
  /** The full error text, e.g. "Name must not be blank". */
  error?: string | null
  className?: string
  children: ReactNode
}

/** A labelled form control with its error below. The label wraps the control, so no ids are needed. */
export function Field({ label, error, className, children }: Props) {
  return (
    <div className={cn('grid content-start gap-1.5', className)}>
      <label className="grid gap-1.5 text-sm font-medium">
        {label}
        <span className="contents font-normal">{children}</span>
      </label>
      {error && <ErrorText>{error}</ErrorText>}
    </div>
  )
}

export function ErrorText({ children }: { children: ReactNode }) {
  return (
    <p role="alert" className="text-sm text-destructive">
      {children}
    </p>
  )
}

/** A native checkbox with its label, in the accent color. */
export function CheckboxField({
  label,
  checked,
  onChange,
}: {
  label: ReactNode
  checked: boolean
  onChange: (checked: boolean) => void
}) {
  return (
    <label className="inline-flex min-h-9 items-center gap-2 text-sm">
      <input
        type="checkbox"
        className="size-4 rounded accent-brand"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      {label}
    </label>
  )
}

/** The submit and cancel row at the bottom of a form card. */
export function FormActions({ children }: { children: ReactNode }) {
  return <div className="flex flex-wrap gap-2 border-t pt-5 md:col-span-2">{children}</div>
}

/** A form laid out as a card: one column on phones, two from medium screens up. */
export const formCard = 'grid max-w-3xl gap-5 rounded-xl border bg-card p-4 shadow-xs sm:p-6 md:grid-cols-2'
