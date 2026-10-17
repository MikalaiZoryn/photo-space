import type { ReactNode } from 'react'
import { cn } from 'cn'

/** Read-only details as a card of label/value pairs: stacked on phones, a two-column grid from small screens up. */
export function Fields({ children }: { children: ReactNode }) {
  return <dl className="grid gap-x-6 gap-y-4 rounded-xl border bg-card p-4 shadow-xs sm:grid-cols-2 sm:p-6">{children}</dl>
}

/** One label/value pair; `wide` spans both columns. */
export function FieldItem({ label, wide, children }: { label: string; wide?: boolean; children: ReactNode }) {
  return (
    <div className={cn('min-w-0 space-y-1', wide && 'sm:col-span-2')}>
      <dt className="text-xs font-medium tracking-wide text-muted-foreground uppercase">{label}</dt>
      <dd className="text-sm break-words [&_a]:font-medium [&_a]:text-accent-foreground [&_a:hover]:underline">{children}</dd>
    </div>
  )
}
