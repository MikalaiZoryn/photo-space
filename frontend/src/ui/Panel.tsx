import type { ReactNode } from 'react'
import { cn } from 'cn'

type Props = {
  /** The heading's id; the panel is a section labelled by it. */
  id: string
  title: ReactNode
  actions?: ReactNode
  className?: string
  children: ReactNode
}

/** A white card section with a heading row, used for the parts of a detail page. */
export function Panel({ id, title, actions, className, children }: Props) {
  return (
    <section
      aria-labelledby={id}
      className={cn('space-y-4 rounded-xl border bg-card p-4 text-card-foreground shadow-xs sm:p-6', className)}
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h4 id={id} className="text-base font-semibold">
          {title}
        </h4>
        {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
      </div>
      {children}
    </section>
  )
}
