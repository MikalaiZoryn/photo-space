import { paths } from '../routing.ts'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Panel } from '../ui/Panel.tsx'
import { BatchList } from './BatchList.tsx'

/** Every batch of drafts. The brand profile they follow is in Settings, under Content Studio. */
export function ContentPage({ workspaceId, timezone }: { workspaceId: string; timezone: string }) {
  return (
    <section aria-labelledby="content-heading">
      <PageHeader
        id="content-heading"
        title="Content"
        description={
          <>
            AI-written Instagram drafts for you to edit and approve. Nothing is ever posted for you. To create drafts,
            open a session with ready photos. Drafts follow the brand profile in{' '}
            <a className="font-medium text-accent-foreground hover:underline" href={paths.contentStudio()}>
              Content Studio
            </a>
            .
          </>
        }
      />
      <Panel id="batches-heading" title="Drafts">
        <BatchList workspaceId={workspaceId} timezone={timezone} emptyText="No drafts yet." />
      </Panel>
    </section>
  )
}
