import { BrandProfilePanel } from '../content/BrandProfileForm.tsx'
import { StudioSettingsPanel } from '../content/StudioSettingsForm.tsx'
import { paths } from '../routing.ts'
import { PageHeader } from '../ui/PageHeader.tsx'

/** Content Studio configuration that persists across sessions: the plan template, the weekly goal, and the brand profile. */
export function ContentStudioSettingsPage({ workspaceId }: { workspaceId: string }) {
  return (
    <section aria-labelledby="content-studio-settings-heading" className="space-y-6">
      <PageHeader
        id="content-studio-settings-heading"
        title="Content Studio"
        description="How session plans are built, how often you aim to post, and how your drafts sound."
        back={{ href: paths.settings(), label: 'Settings' }}
      />
      <StudioSettingsPanel workspaceId={workspaceId} />
      <BrandProfilePanel workspaceId={workspaceId} />
    </section>
  )
}
