import type { CSSProperties } from 'react'
import type { BrandFont, PublicBranding } from '../api/branding.ts'

/** Each font is bundled (Inter) or a system font stack, so public pages make no third-party font requests. */
export const fontStacks: Record<BrandFont, string> = {
  INTER: '"Inter Variable", ui-sans-serif, system-ui, sans-serif',
  SYSTEM: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
  SERIF: 'ui-serif, Georgia, Cambria, "Times New Roman", serif',
  ROUNDED: 'ui-rounded, "SF Pro Rounded", "Arial Rounded MT Bold", system-ui, sans-serif',
}

export const fontLabels: Record<BrandFont, string> = {
  INTER: 'Inter (clean sans-serif)',
  SYSTEM: 'System (the device’s own font)',
  SERIF: 'Serif (classic)',
  ROUNDED: 'Rounded (friendly)',
}

const HEX = /^#[0-9a-fA-F]{6}$/

/** Black or white, whichever reads better on `hex` (WCAG relative luminance). */
export function readableOn(hex: string): string {
  if (!HEX.test(hex)) return '#ffffff'
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
  return luminance > 0.179 ? '#111111' : '#ffffff'
}

/**
 * The branding as CSS custom properties and a font family. Colors are validated hex values from the server, and
 * React sets them as style properties, so branding can never inject CSS.
 */
export function brandStyle(branding: PublicBranding): CSSProperties {
  const color = (value: string, fallback: string) => (HEX.test(value) ? value : fallback)
  const primary = color(branding.primaryColor, '#18181b')
  return {
    '--brand-primary': primary,
    '--brand-on-primary': readableOn(primary),
    '--brand-accent': color(branding.accentColor, '#7c3aed'),
    fontFamily: fontStacks[branding.font] ?? fontStacks.INTER,
  } as CSSProperties
}
