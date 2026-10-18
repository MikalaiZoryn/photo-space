import type { CaptionRule } from '../api/content.ts'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { ruleTones } from './labels.ts'

const severityLabels: Record<CaptionRule['severity'], string> = {
  BLOCKER: 'Fix before saving',
  WARNING: 'Check',
  TIP: 'Tip',
}

/** The caption rules the saved version breaks, as fix-it chips. Nothing shows when it follows them all. */
export function CaptionRules({ rules }: { rules: CaptionRule[] }) {
  if (rules.length === 0) return null
  return (
    <ul className="space-y-2" aria-label="Caption checks">
      {rules.map((rule) => (
        <li key={rule.code} className="flex flex-wrap items-start gap-2 text-sm">
          <StatusBadge tone={ruleTones[rule.severity]}>{severityLabels[rule.severity]}</StatusBadge>
          <span className="min-w-0 flex-1 pt-0.5">{rule.message}</span>
        </li>
      ))}
    </ul>
  )
}
