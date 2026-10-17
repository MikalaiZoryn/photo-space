import type { ReactNode } from 'react'
import { ChevronLeft } from 'lucide-react'

type Props = {
  /** The heading's id, for the section's aria-labelledby. */
  id: string
  title: ReactNode
  /** Shown next to the title, outside the heading, e.g. status badges. */
  badges?: ReactNode
  description?: ReactNode
  actions?: ReactNode
  back?: { href: string; label: string }
}

/** A page's title row: optional back link, title with badges, description, and actions on the right. */
export function PageHeader({ id, title, badges, description, actions, back }: Props) {
  return (
    <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div className="min-w-0 space-y-1.5">
        {back && (
          <a
            href={back.href}
            className="inline-flex items-center gap-1 text-sm font-medium text-muted-foreground hover:text-foreground"
          >
            <ChevronLeft className="size-4" aria-hidden="true" />
            {back.label}
          </a>
        )}
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <h3 id={id} className="text-2xl font-semibold break-words sm:text-3xl">
            {title}
          </h3>
          {badges}
        </div>
        {description && <p className="text-sm text-muted-foreground">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 flex-wrap gap-2">{actions}</div>}
    </div>
  )
}
