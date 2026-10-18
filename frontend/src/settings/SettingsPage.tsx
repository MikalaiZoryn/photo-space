import type { LucideIcon } from 'lucide-react'
import { ClipboardList, Sparkles, UserCog, Workflow } from 'lucide-react'
import { paths } from '../routing.ts'
import { PageHeader } from '../ui/PageHeader.tsx'

type Card = { id: string; href: string; title: string; description: string; icon: LucideIcon }

const CARDS: Card[] = [
  {
    id: 'automation',
    href: paths.automation(),
    title: 'Automation',
    description: 'Rules that add tasks when a session changes status.',
    icon: Workflow,
  },
  {
    id: 'members',
    href: paths.members(),
    title: 'Members',
    description: 'People in this workspace and invitations.',
    icon: UserCog,
  },
  {
    id: 'inquiry-form',
    href: paths.inquiryForm(),
    title: 'Inquiry form',
    description: 'The public inquiry form and its branding.',
    icon: ClipboardList,
  },
  {
    id: 'content-studio',
    href: paths.contentStudioSettings(),
    title: 'Content Studio',
    description: 'Posting goal, plan template, and brand profile.',
    icon: Sparkles,
  },
]

/** The workspace's configuration pages, one square card each. */
export function SettingsPage() {
  return (
    <section aria-labelledby="settings-heading">
      <PageHeader id="settings-heading" title="Settings" description="How this workspace works and looks." />
      <ul aria-label="Settings" className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
        {CARDS.map((card) => (
          <li key={card.id}>
            <a
              href={card.href}
              aria-labelledby={`settings-card-${card.id}`}
              aria-describedby={`settings-card-${card.id}-description`}
              className="flex aspect-square flex-col items-center justify-center gap-2 rounded-xl border bg-card p-3 text-center text-card-foreground shadow-xs transition-colors outline-none hover:bg-muted focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 sm:gap-3 sm:p-4"
            >
              <span className="flex size-10 items-center justify-center rounded-lg bg-brand text-brand-foreground sm:size-12">
                <card.icon className="size-5 sm:size-6" aria-hidden="true" />
              </span>
              <span id={`settings-card-${card.id}`} className="text-base font-semibold">
                {card.title}
              </span>
              <span id={`settings-card-${card.id}-description`} className="text-xs text-muted-foreground sm:text-sm">
                {card.description}
              </span>
            </a>
          </li>
        ))}
      </ul>
    </section>
  )
}
