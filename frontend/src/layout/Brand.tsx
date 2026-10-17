import { Aperture } from 'lucide-react'

/** The product wordmark. */
export function Brand() {
  return (
    <span className="flex items-center gap-2.5 text-base font-semibold tracking-tight">
      <span className="flex size-8 items-center justify-center rounded-lg bg-brand text-brand-foreground">
        <Aperture className="size-5" aria-hidden="true" />
      </span>
      Photo Space
    </span>
  )
}
