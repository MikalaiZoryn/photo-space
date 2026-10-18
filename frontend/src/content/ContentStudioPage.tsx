import { useState } from 'react'
import { listPlans, listPosts, type PostStatus } from '../api/content.ts'
import { CONTENT_VIEWS, paths, type ContentView } from '../routing.ts'
import { formatDate } from '../time.ts'
import { cn } from 'cn'
import { NativeSelect } from '@/components/ui/native-select'
import { EmptyState, Loading } from '../ui/Feedback.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Pager } from '../ui/Pager.tsx'
import { Panel } from '../ui/Panel.tsx'
import { FilterBar, RecordList, RecordRow } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { useLoad } from '../useLoad.ts'
import { BatchList } from './BatchList.tsx'
import { ContentCalendar } from './ContentCalendar.tsx'
import { batchStatusLabels, batchStatusTones, postStatusLabels } from './labels.ts'
import { PostRow } from './PostRow.tsx'

const viewLabels: Record<ContentView, string> = {
  calendar: 'Calendar',
  review: 'To review',
  posts: 'All posts',
  captions: 'Caption drafts',
}

type Props = { workspaceId: string; timezone: string; view: ContentView }

/**
 * The Content Studio: every session's planned posts on one calendar, the plans waiting for review, and every
 * post by status. Plans are proposed from a session's photos; a person approves each post and posts it by hand.
 */
export function ContentStudioPage({ workspaceId, timezone, view }: Props) {
  return (
    <section aria-labelledby="content-heading">
      <PageHeader
        id="content-heading"
        title="Content Studio"
        description={
          <>
            Instagram posts planned from your sessions. Nothing is ever posted for you: approve a post, then post it
            from its post kit. Plans and posting goals are set in{' '}
            <a className="font-medium text-accent-foreground hover:underline" href={paths.contentStudioSettings()}>
              Settings
            </a>
            .
          </>
        }
      />
      <nav aria-label="Content Studio views" className="mb-6 flex gap-1 overflow-x-auto overflow-y-hidden border-b">
        {CONTENT_VIEWS.map((v) => (
          <a
            key={v}
            href={paths.content(v)}
            aria-current={v === view ? 'page' : undefined}
            className={cn(
              '-mb-px shrink-0 border-b-2 px-3 py-2 text-sm font-medium whitespace-nowrap',
              v === view
                ? 'border-brand text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground',
            )}
          >
            {viewLabels[v]}
          </a>
        ))}
      </nav>
      {view === 'calendar' && <ContentCalendar workspaceId={workspaceId} timezone={timezone} />}
      {view === 'review' && <PlansToReview workspaceId={workspaceId} timezone={timezone} />}
      {view === 'posts' && <AllPosts workspaceId={workspaceId} />}
      {view === 'captions' && (
        <Panel id="batches-heading" title="Caption drafts">
          <p className="text-sm text-muted-foreground">
            Caption options requested for chosen photos from a session page, outside any plan.
          </p>
          <BatchList workspaceId={workspaceId} timezone={timezone} emptyText="No caption drafts yet." />
        </Panel>
      )}
    </section>
  )
}

/** Plans that are generating, failed, or have posts waiting for approval. */
function PlansToReview({ workspaceId, timezone }: { workspaceId: string; timezone: string }) {
  const [pageNumber, setPageNumber] = useState(0)
  const plans = useLoad(
    (signal) => listPlans(workspaceId, { toReview: true, page: pageNumber }, signal),
    [workspaceId, pageNumber],
  )

  if (plans.state.kind === 'loading') return <Loading>Loading plans…</Loading>
  if (plans.state.kind === 'error') return <LoadError error={plans.state.error} what="plans" onRetry={plans.reload} />
  const page = plans.state.value
  if (page.totalItems === 0) {
    return <EmptyState>Nothing to review. New plans appear here when a session&apos;s photos are ready.</EmptyState>
  }
  return (
    <>
      <RecordList label="Plans to review">
        {page.items.map((plan) => (
          <RecordRow
            key={plan.id}
            href={paths.contentPlan(plan.sessionId)}
            title={plan.sessionTitle}
            meta={[
              plan.status === 'SUCCEEDED' && `${plan.proposedCount} to review`,
              plan.status === 'SUCCEEDED' && `${plan.approvedCount} approved`,
              plan.postedCount > 0 && `${plan.postedCount} posted`,
              `updated ${formatDate(plan.updatedAt, timezone)}`,
            ]
              .filter(Boolean)
              .join(' · ')}
            trailing={
              plan.status !== 'SUCCEEDED' && (
                <StatusBadge tone={batchStatusTones[plan.status]}>{batchStatusLabels[plan.status]}</StatusBadge>
              )
            }
          />
        ))}
      </RecordList>
      <Pager page={page} onPage={setPageNumber} />
    </>
  )
}

const STATUSES: PostStatus[] = ['PROPOSED', 'APPROVED', 'SCHEDULED', 'POSTED']

/** Every post in calendar order, optionally of one status. */
function AllPosts({ workspaceId }: { workspaceId: string }) {
  const [status, setStatus] = useState<PostStatus | ''>('')
  const [pageNumber, setPageNumber] = useState(0)
  const posts = useLoad(
    (signal) => listPosts(workspaceId, { status: status || undefined, page: pageNumber }, signal),
    [workspaceId, status, pageNumber],
  )

  return (
    <div className="space-y-4">
      <FilterBar>
        <label className="flex items-center gap-2 text-sm">
          Status
          <NativeSelect
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as PostStatus | '')
              setPageNumber(0)
            }}
          >
            <option value="">All</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {postStatusLabels[s]}
              </option>
            ))}
          </NativeSelect>
        </label>
      </FilterBar>
      {posts.state.kind === 'loading' && <Loading>Loading posts…</Loading>}
      {posts.state.kind === 'error' && <LoadError error={posts.state.error} what="posts" onRetry={posts.reload} />}
      {posts.state.kind === 'loaded' &&
        (posts.state.value.totalItems === 0 ? (
          <EmptyState>No posts yet. Plans are proposed when a session&apos;s photos are ready.</EmptyState>
        ) : (
          <>
            <RecordList label="Posts">
              {posts.state.value.items.map((post) => (
                <PostRow key={post.id} post={post} />
              ))}
            </RecordList>
            <Pager page={posts.state.value} onPage={setPageNumber} />
          </>
        ))}
    </div>
  )
}
