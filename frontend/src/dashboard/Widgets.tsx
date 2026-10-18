import { useState, type ReactNode } from 'react'
import { CalendarPlus, Link as LinkIcon, ListPlus, UserPlus } from 'lucide-react'
import { cn } from 'cn'
import { Button } from '@/components/ui/button'
import { actionLabels, targetHref } from '../activity/labels.ts'
import { listAuditEvents } from '../api/audit.ts'
import type { Page } from '../api/client.ts'
import { getStudioSettings, listBatches, listPosts } from '../api/content.ts'
import { addDays, countsTowardGoal, todayIn, weekStart } from '../content/calendar.ts'
import { postStatusLabels, postStatusTones, formatLabels } from '../content/labels.ts'
import { listGalleries } from '../api/galleries.ts'
import { listInquiries } from '../api/inquiries.ts'
import { getInquiryForm, hostedUrl } from '../api/inquiryForm.ts'
import { listSessions } from '../api/sessions.ts'
import { listTasks } from '../api/tasks.ts'
import { galleryStatusLabels, galleryStatusTones } from '../galleries/labels.ts'
import { inquiryStatusLabels, inquiryStatusTones } from '../inquiries/labels.ts'
import { paths } from '../routing.ts'
import { statusLabels, statusTones } from '../sessions/status.ts'
import { formatDate, formatDay, formatSessionTime } from '../time.ts'
import { Loading } from '../ui/Feedback.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { RecordRow } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { useLoad, type Loadable } from '../useLoad.ts'

/** What every widget gets. Each loads its own data, so one failing widget leaves the others working. */
export type WidgetProps = { workspaceId: string; timezone: string; userId: string }

/** How many rows a list widget shows; its "View all" link opens the full list. */
const ROWS = 5

/** A widget's list: loading, error with retry, empty text, or up to {@link ROWS} rows. */
function WidgetList<T>({
  state,
  reload,
  what,
  emptyText,
  children,
}: {
  state: Loadable<Page<T>>
  reload: () => void
  what: string
  emptyText: string
  children: (item: T) => ReactNode
}) {
  if (state.kind === 'loading') {
    return (
      <div className="px-4">
        <Loading>Loading {what}…</Loading>
      </div>
    )
  }
  if (state.kind === 'error') {
    return (
      <div className="p-4">
        <LoadError error={state.error} what={what} onRetry={reload} />
      </div>
    )
  }
  if (state.value.totalItems === 0) return <p className="px-4 py-6 text-sm text-muted-foreground">{emptyText}</p>
  return (
    <ul aria-label={what} className="divide-y">
      {state.value.items.map(children)}
    </ul>
  )
}

export function AtAGlance({ workspaceId }: WidgetProps) {
  const counts = useLoad(async (signal) => {
    const one = { size: 1 }
    const [sessions, open, overdue, inquiries, content] = await Promise.all([
      listSessions(workspaceId, { ...one, upcoming: true }, signal),
      listTasks(workspaceId, { ...one, status: 'OPEN' }, signal),
      listTasks(workspaceId, { ...one, overdue: true }, signal),
      listInquiries(workspaceId, { ...one, open: true }, signal),
      listBatches(workspaceId, { ...one, toReview: true }, signal),
    ])
    return [
      { label: 'Upcoming sessions', value: sessions.totalItems, href: paths.sessions() },
      { label: 'Open tasks', value: open.totalItems, href: paths.tasks() },
      { label: 'Overdue tasks', value: overdue.totalItems, href: paths.tasks(), alert: overdue.totalItems > 0 },
      { label: 'Open inquiries', value: inquiries.totalItems, href: paths.inquiries() },
      { label: 'Content to review', value: content.totalItems, href: paths.content() },
    ]
  }, [workspaceId])

  if (counts.state.kind === 'loading') {
    return (
      <div className="px-4">
        <Loading>Loading counts…</Loading>
      </div>
    )
  }
  if (counts.state.kind === 'error') {
    return (
      <div className="p-4">
        <LoadError error={counts.state.error} what="counts" onRetry={counts.reload} />
      </div>
    )
  }
  return (
    <div className="@container p-4">
      <ul aria-label="Counts" className="grid grid-cols-2 gap-3 @lg:grid-cols-3 @3xl:grid-cols-5">
        {counts.state.value.map((stat) => (
          <li key={stat.label}>
            <a
              href={stat.href}
              className="flex h-full flex-col gap-1 rounded-lg border bg-background px-3 py-3 transition-colors hover:bg-muted/60"
            >
              <span className={cn('text-2xl font-semibold tabular-nums', stat.alert && 'text-tone-danger')}>
                {stat.value}
              </span>{' '}
              <span className="text-sm text-muted-foreground">{stat.label}</span>
            </a>
          </li>
        ))}
      </ul>
    </div>
  )
}

export function UpcomingSessions({ workspaceId, timezone }: WidgetProps) {
  const sessions = useLoad(
    (signal) => listSessions(workspaceId, { upcoming: true, size: ROWS }, signal),
    [workspaceId],
  )
  return (
    <WidgetList state={sessions.state} reload={sessions.reload} what="upcoming sessions" emptyText="No upcoming sessions.">
      {(session) => (
        <RecordRow
          key={session.id}
          href={paths.session(session.id)}
          title={session.title}
          meta={[formatSessionTime(session.startsAt, session.endsAt, timezone), session.client?.name]
            .filter(Boolean)
            .join(' · ')}
          trailing={<StatusBadge tone={statusTones[session.status]}>{statusLabels[session.status]}</StatusBadge>}
        />
      )}
    </WidgetList>
  )
}

export function MyTasks({ workspaceId, userId }: WidgetProps) {
  const tasks = useLoad(
    (signal) => listTasks(workspaceId, { status: 'OPEN', assigneeId: userId, size: ROWS }, signal),
    [workspaceId, userId],
  )
  return (
    <WidgetList state={tasks.state} reload={tasks.reload} what="your tasks" emptyText="No open tasks assigned to you.">
      {(task) => (
        <RecordRow
          key={task.id}
          href={paths.taskEdit(task.id)}
          title={task.title}
          meta={[task.dueDate && `Due ${formatDay(task.dueDate)}`, task.session?.title ?? task.client?.name]
            .filter(Boolean)
            .join(' · ')}
          trailing={task.overdue && <StatusBadge tone="danger">Overdue</StatusBadge>}
        />
      )}
    </WidgetList>
  )
}

export function OpenInquiries({ workspaceId, timezone }: WidgetProps) {
  const inquiries = useLoad((signal) => listInquiries(workspaceId, { open: true, size: ROWS }, signal), [workspaceId])
  return (
    <WidgetList state={inquiries.state} reload={inquiries.reload} what="open inquiries" emptyText="No open inquiries.">
      {(inquiry) => (
        <RecordRow
          key={inquiry.id}
          href={paths.inquiry(inquiry.id)}
          title={inquiry.name}
          meta={[inquiry.service, `Received ${formatDate(inquiry.submittedAt, timezone)}`].filter(Boolean).join(' · ')}
          trailing={
            <StatusBadge tone={inquiryStatusTones[inquiry.status]}>{inquiryStatusLabels[inquiry.status]}</StatusBadge>
          }
        />
      )}
    </WidgetList>
  )
}

export function ContentToReview({ workspaceId, timezone }: WidgetProps) {
  const batches = useLoad((signal) => listBatches(workspaceId, { toReview: true, size: ROWS }, signal), [workspaceId])
  return (
    <WidgetList state={batches.state} reload={batches.reload} what="content to review" emptyText="Nothing to review.">
      {(batch) => (
        <RecordRow
          key={batch.id}
          href={paths.contentBatch(batch.id)}
          title={batch.sessionTitle}
          meta={`${batch.approvedCount} of ${batch.draftCount} drafts approved · ${formatDate(batch.createdAt, timezone)}`}
        />
      )}
    </WidgetList>
  )
}

/** The feed posts of this week against the weekly goal, then each post planned or posted this week. */
export function ThisWeekPosts({ workspaceId, timezone }: WidgetProps) {
  const from = weekStart(todayIn(timezone))
  const data = useLoad(
    (signal) =>
      Promise.all([
        getStudioSettings(workspaceId, signal),
        listPosts(workspaceId, { from, to: addDays(from, 6), size: 50 }, signal),
      ]),
    [workspaceId, from],
  )
  if (data.state.kind === 'loading') {
    return (
      <div className="px-4">
        <Loading>Loading this week&apos;s posts…</Loading>
      </div>
    )
  }
  if (data.state.kind === 'error') {
    return (
      <div className="p-4">
        <LoadError error={data.state.error} what="this week's posts" onRetry={data.reload} />
      </div>
    )
  }
  const [settings, posts] = data.state.value
  const ready = posts.items.filter((post) => countsTowardGoal(post) && post.status !== 'PROPOSED').length
  return (
    <div>
      <p className="px-4 pt-3 text-sm">
        <span className="font-semibold">
          {ready} of {settings.weeklyGoal}
        </span>{' '}
        <span className="text-muted-foreground">feed posts approved or posted this week</span>
      </p>
      {posts.items.length === 0 ? (
        <p className="px-4 py-6 text-sm text-muted-foreground">Nothing planned this week.</p>
      ) : (
        <ul className="divide-y">
          {posts.items.slice(0, ROWS).map((post) => (
            <RecordRow
              key={post.id}
              href={paths.contentPost(post.id)}
              title={`${formatLabels[post.format]} · ${post.sessionTitle}`}
              meta={formatDay(post.postedOn ?? post.plannedOn ?? from)}
              trailing={<StatusBadge tone={postStatusTones[post.status]}>{postStatusLabels[post.status]}</StatusBadge>}
            />
          ))}
        </ul>
      )}
    </div>
  )
}

export function RecentGalleries({ workspaceId }: WidgetProps) {
  const galleries = useLoad((signal) => listGalleries(workspaceId, { size: ROWS }, signal), [workspaceId])
  return (
    <WidgetList state={galleries.state} reload={galleries.reload} what="galleries" emptyText="No galleries yet.">
      {(gallery) => (
        <RecordRow
          key={gallery.id}
          href={paths.gallery(gallery.id)}
          title={gallery.title}
          meta={gallery.photoCount === 1 ? '1 photo' : `${gallery.photoCount} photos`}
          trailing={
            <StatusBadge tone={galleryStatusTones[gallery.status]}>{galleryStatusLabels[gallery.status]}</StatusBadge>
          }
        />
      )}
    </WidgetList>
  )
}

export function RecentActivity({ workspaceId, timezone }: WidgetProps) {
  const events = useLoad((signal) => listAuditEvents(workspaceId, { size: ROWS }, signal), [workspaceId])
  return (
    <WidgetList state={events.state} reload={events.reload} what="recent activity" emptyText="No activity yet.">
      {(event) => (
        <RecordRow
          key={event.id}
          href={targetHref(event) ?? undefined}
          title={actionLabels[event.action] ?? event.action}
          meta={[event.actor?.displayName ?? 'Automation', formatSessionTime(event.createdAt, null, timezone)]
            .filter(Boolean)
            .join(' · ')}
        />
      )}
    </WidgetList>
  )
}

export function QuickActions({ workspaceId }: WidgetProps) {
  // The copy button appears only while the form is published; any failure to load just leaves it out.
  const form = useLoad((signal) => getInquiryForm(workspaceId, signal), [workspaceId])
  const [copied, setCopied] = useState(false)
  const slug = form.state.kind === 'loaded' && form.state.value.status === 'PUBLISHED' ? form.state.value.slug : null

  async function copy(url: string) {
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
    } catch {
      // Clipboard access can be refused; the link is also on the Inquiry form page.
    }
  }

  const link = 'justify-start'
  return (
    <div className="grid gap-2 p-4 sm:grid-cols-2">
      <Button asChild variant="outline" className={link}>
        <a href={paths.sessionNew()}>
          <CalendarPlus aria-hidden="true" />
          New session
        </a>
      </Button>
      <Button asChild variant="outline" className={link}>
        <a href={paths.clientNew()}>
          <UserPlus aria-hidden="true" />
          New client
        </a>
      </Button>
      <Button asChild variant="outline" className={link}>
        <a href={paths.taskNew()}>
          <ListPlus aria-hidden="true" />
          New task
        </a>
      </Button>
      {slug !== null && (
        <Button type="button" variant="outline" className={link} onClick={() => copy(hostedUrl(slug))}>
          <LinkIcon aria-hidden="true" />
          {copied ? 'Inquiry form link copied' : 'Copy inquiry form link'}
        </Button>
      )}
    </div>
  )
}
