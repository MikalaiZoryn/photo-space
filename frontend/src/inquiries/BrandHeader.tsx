import type { PublicBranding } from '../api/branding.ts'

/** The business as visitors see it: logo, name, short description, and contact details. */
export function BrandHeader({ branding, compact = false }: { branding: PublicBranding; compact?: boolean }) {
  return (
    <header className={compact ? 'flex items-center gap-3' : 'flex flex-col items-center gap-3 text-center'}>
      {branding.logoUrl && (
        <img
          src={branding.logoUrl}
          alt={`${branding.displayName} logo`}
          className={compact ? 'h-10 w-auto max-w-32 object-contain' : 'h-16 w-auto max-w-48 object-contain sm:h-20'}
        />
      )}
      <div className="min-w-0 space-y-1">
        <p className={compact ? 'font-semibold' : 'text-lg font-semibold'}>{branding.displayName}</p>
        {!compact && branding.description && (
          <p className="max-w-prose text-sm text-muted-foreground">{branding.description}</p>
        )}
        {!compact && (branding.email || branding.phone) && (
          <p className="flex flex-wrap justify-center gap-x-4 gap-y-1 text-sm">
            {branding.email && (
              <a className="font-medium text-[var(--brand-accent)] hover:underline" href={`mailto:${branding.email}`}>
                {branding.email}
              </a>
            )}
            {branding.phone && (
              <a className="font-medium text-[var(--brand-accent)] hover:underline" href={`tel:${branding.phone.replace(/[^0-9+]/g, '')}`}>
                {branding.phone}
              </a>
            )}
          </p>
        )}
      </div>
    </header>
  )
}
