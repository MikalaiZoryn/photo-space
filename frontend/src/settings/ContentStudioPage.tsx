import { BrandProfilePanel } from '../content/BrandProfileForm.tsx'
import { paths } from '../routing.ts'
import { PageHeader } from '../ui/PageHeader.tsx'

/** Settings for AI-written content: the brand profile every draft follows. */
export function ContentStudioPage({ workspaceId }: { workspaceId: string }) {
  return (
    <section aria-labelledby="content-studio-heading">
      <PageHeader
        id="content-studio-heading"
        title="Content Studio"
        description="How your Instagram drafts sound and who they are written for."
        back={{ href: paths.settings(), label: 'Settings' }}
      />
      <BrandProfilePanel workspaceId={workspaceId} />
    </section>
  )
}
