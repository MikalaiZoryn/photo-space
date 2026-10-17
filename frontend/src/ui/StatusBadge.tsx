import type { ReactNode } from 'react'
import { cn } from 'cn'
import { Badge } from '@/components/ui/badge'

export type Tone = 'info' | 'success' | 'warning' | 'danger' | 'neutral'

const toneClasses: Record<Tone, string> = {
  info: 'bg-tone-info-bg text-tone-info',
  success: 'bg-tone-success-bg text-tone-success',
  warning: 'bg-tone-warning-bg text-tone-warning',
  danger: 'bg-tone-danger-bg text-tone-danger',
  neutral: 'bg-tone-neutral-bg text-tone-neutral',
}

/** A tinted status pill. The text is the status itself; the tone only reinforces it. */
export function StatusBadge({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return (
    <Badge variant="secondary" className={cn('h-6 rounded-full px-2.5 font-medium', toneClasses[tone])}>
      {children}
    </Badge>
  )
}
