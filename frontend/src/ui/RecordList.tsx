import type { ReactNode } from 'react'
import { ChevronRight } from 'lucide-react'
import { cn } from 'cn'

/** A card holding rows separated by dividers. */
export function RecordList({ label, children }: { label?: string; children: ReactNode }) {
  return (
    <ul aria-label={label} className="divide-y overflow-hidden rounded-xl border bg-card shadow-xs">
      {children}
    </ul>
  )
}

type RowProps = {
  /** Makes the whole row a link to this page. */
  href?: string
  title: ReactNode
  meta?: ReactNode
  leading?: ReactNode
  trailing?: ReactNode
  className?: string
}

/** One row: optional leading avatar, a title (a link that covers the row), muted details, and trailing badges. */
export function RecordRow({ href, title, meta, leading, trailing, className }: RowProps) {
  return (
    <li
      className={cn(
        'relative flex items-center gap-3 px-4 py-3.5 transition-colors sm:gap-4',
        href && 'hover:bg-muted/60',
        className,
      )}
    >
      {leading}
      <div className="min-w-0 flex-1">
        {href ? (
          <a
            href={href}
            className="font-medium break-words after:absolute after:inset-0 after:content-[''] focus-visible:ring-0 focus-visible:after:rounded-xl focus-visible:after:ring-3 focus-visible:after:ring-ring/50"
          >
            {title}
          </a>
        ) : (
          <div className="font-medium">{title}</div>
        )}
        {meta && <p className="mt-0.5 text-sm break-words text-muted-foreground">{meta}</p>}
      </div>
      {trailing && <div className="relative flex shrink-0 flex-wrap items-center justify-end gap-1.5">{trailing}</div>}
      {href && <ChevronRight className="hidden size-4 shrink-0 text-muted-foreground sm:block" aria-hidden="true" />}
    </li>
  )
}

/** Initials in a tinted circle. */
export function Initials({ name, className }: { name: string; className?: string }) {
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('')
  return (
    <span
      aria-hidden="true"
      className={cn(
        'flex size-9 shrink-0 items-center justify-center rounded-full bg-accent text-xs font-semibold text-accent-foreground',
        className,
      )}
    >
      {initials || '?'}
    </span>
  )
}

/** A row of filter controls above a list. */
export function FilterBar({ children }: { children: ReactNode }) {
  return <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2">{children}</div>
}
