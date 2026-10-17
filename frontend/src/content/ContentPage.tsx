import { PageHeader } from '../ui/PageHeader.tsx'
import { Panel } from '../ui/Panel.tsx'
import { BatchList } from './BatchList.tsx'
import { BrandProfilePanel } from './BrandProfileForm.tsx'

/** The content studio's home: the brand profile and every batch of drafts. */
export function ContentPage({ workspaceId, timezone }: { workspaceId: string; timezone: string }) {
  return (
    <section aria-labelledby="content-heading">
      <PageHeader
        id="content-heading"
        title="Content"
        description="AI-written Instagram drafts for you to edit and approve. Nothing is ever posted for you. To create drafts, open a session with ready photos."
      />
      <div className="space-y-6">
        <BrandProfilePanel workspaceId={workspaceId} />
        <Panel id="batches-heading" title="Drafts">
          <BatchList workspaceId={workspaceId} timezone={timezone} emptyText="No drafts yet." />
        </Panel>
      </div>
    </section>
  )
}
