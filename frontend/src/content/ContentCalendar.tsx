import { useState } from 'react'
import {
  DndContext,
  PointerSensor,
  TouchSensor,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import { CalendarClock, ChevronLeft, ChevronRight, TriangleAlert } from 'lucide-react'
import { cn } from 'cn'
import { getStudioSettings, listPosts, schedulePost, type Post } from '../api/content.ts'
import { paths } from '../routing.ts'
import { formatDay } from '../time.ts'
import { toFormError } from '../ui/formError.ts'
import { Button } from '@/components/ui/button'
import { ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { Panel } from '../ui/Panel.tsx'
import { RecordList } from '../ui/RecordList.tsx'
import { useLoad } from '../useLoad.ts'
import {
  addDays,
  addMonths,
  cadenceByWeek,
  dayLabel,
  daysBetween,
  monthLabel,
  monthRange,
  postDay,
  todayIn,
  weekStart,
  type WeekCadence,
} from './calendar.ts'
import { formatLabels, stageLabels } from './labels.ts'
import { PostCover } from './PostRow.tsx'

type Mode = 'week' | 'month'

const statusBorders: Record<Post['status'], string> = {
  PROPOSED: 'border-l-tone-warning',
  APPROVED: 'border-l-tone-info',
  SCHEDULED: 'border-l-tone-info',
  POSTED: 'border-l-tone-success',
}

/**
 * Every post planned or posted in a week or month, with each week's progress toward the weekly goal and a
 * warning for weeks with nothing planned. Dragging a post to another day reschedules it; a post's own page sets
 * the day too, for keyboard and screen reader users.
 */
export function ContentCalendar({ workspaceId, timezone }: { workspaceId: string; timezone: string }) {
  const today = todayIn(timezone)
  const [mode, setMode] = useState<Mode>('week')
  const [anchor, setAnchor] = useState(today)
  const [moveError, setMoveError] = useState<string | null>(null)
  const range = mode === 'week' ? { from: weekStart(anchor), to: addDays(weekStart(anchor), 6) } : monthRange(anchor)
  const data = useLoad(
    (signal) =>
      Promise.all([
        getStudioSettings(workspaceId, signal),
        listPosts(workspaceId, { from: range.from, to: range.to, size: 100 }, signal),
        listPosts(workspaceId, { unscheduled: true, size: 10 }, signal),
      ]),
    [workspaceId, range.from, range.to],
  )
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 250, tolerance: 6 } }),
  )

  function move(step: number) {
    setAnchor(mode === 'week' ? addDays(anchor, step * 7) : addMonths(anchor, step))
  }

  async function reschedule(post: Post, day: string) {
    setMoveError(null)
    try {
      await schedulePost(workspaceId, post.id, post.version, day)
      data.reload()
    } catch (e) {
      setMoveError(toFormError(e, 'move the post').message ?? 'Could not move the post.')
    }
  }

  async function onDragEnd(event: DragEndEvent) {
    const post = event.active.data.current?.post as Post | undefined
    const day = event.over?.id
    if (post && typeof day === 'string' && day !== postDay(post)) await reschedule(post, day)
  }

  const title = mode === 'week' ? `Week of ${formatDay(range.from)}` : monthLabel(anchor)

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-1">
          <Button type="button" variant="outline" size="icon" aria-label="Previous" onClick={() => move(-1)}>
            <ChevronLeft aria-hidden="true" />
          </Button>
          <Button type="button" variant="outline" onClick={() => setAnchor(today)}>
            Today
          </Button>
          <Button type="button" variant="outline" size="icon" aria-label="Next" onClick={() => move(1)}>
            <ChevronRight aria-hidden="true" />
          </Button>
          <h4 className="ml-2 text-base font-semibold" aria-live="polite">
            {title}
          </h4>
        </div>
        <div role="group" aria-label="Calendar view" className="flex rounded-lg border p-0.5">
          {(['week', 'month'] as const).map((m) => (
            <button
              key={m}
              type="button"
              aria-pressed={mode === m}
              onClick={() => setMode(m)}
              className={cn(
                'rounded-md px-3 py-1 text-sm font-medium',
                mode === m ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {m === 'week' ? 'Week' : 'Month'}
            </button>
          ))}
        </div>
      </div>
      {moveError && <ErrorAlert>{moveError}</ErrorAlert>}
      {data.state.kind === 'loading' && <Loading>Loading the calendar…</Loading>}
      {data.state.kind === 'error' && <LoadError error={data.state.error} what="calendar" onRetry={data.reload} />}
      {data.state.kind === 'loaded' &&
        (() => {
          const [settings, posts, unscheduled] = data.state.value
          const weeks = daysBetween(range.from, range.to).filter((_, i) => i % 7 === 0)
          const cadence = cadenceByWeek(posts.items, weeks)
          const gaps = cadence.filter((week) => addDays(week.start, 6) >= today && week.ready + week.proposed === 0)
          return (
            <>
              <CadenceMeter cadence={cadence} goal={settings.weeklyGoal} />
              {gaps.length > 0 && (
                <GapWarning
                  gaps={gaps}
                  suggestions={unscheduled.items}
                  onPlan={(post, day) => void reschedule(post, day)}
                />
              )}
              <DndContext sensors={sensors} onDragEnd={(event) => void onDragEnd(event)}>
                <div className={cn('grid gap-2', mode === 'week' ? 'sm:grid-cols-7' : 'grid-cols-7')}>
                  {daysBetween(range.from, range.to).map((day) => (
                    <DayCell
                      key={day}
                      day={day}
                      today={day === today}
                      compact={mode === 'month'}
                      outside={mode === 'month' && day.slice(0, 7) !== anchor.slice(0, 7)}
                      posts={posts.items.filter((post) => postDay(post) === day)}
                    />
                  ))}
                </div>
              </DndContext>
              <p className="text-xs text-muted-foreground">
                Drag a post to another day to move it, or set the day on the post itself. Stories are shown but do
                not count toward the weekly goal.
              </p>
            </>
          )
        })()}
    </div>
  )
}

/** Each week's approved and posted feed posts against the goal, with proposals shown apart. */
function CadenceMeter({ cadence, goal }: { cadence: WeekCadence[]; goal: number }) {
  return (
    <ul aria-label="Weekly goal" className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
      {cadence.map((week) => {
        const met = week.ready >= goal
        return (
          <li key={week.start} className="rounded-xl border bg-card p-3 shadow-xs">
            <div className="flex items-baseline justify-between gap-2 text-sm">
              <span className="font-medium">Week of {formatDay(week.start)}</span>
              <span className={met ? 'text-tone-success' : 'text-muted-foreground'}>
                {week.ready} of {goal} posts
              </span>
            </div>
            <div
              role="progressbar"
              aria-label={`Week of ${formatDay(week.start)}`}
              aria-valuemin={0}
              aria-valuemax={goal}
              aria-valuenow={Math.min(week.ready, goal)}
              className="mt-2 h-2 overflow-hidden rounded-full bg-muted"
            >
              <div
                className={cn('h-full rounded-full', met ? 'bg-tone-success' : 'bg-brand')}
                style={{ width: `${Math.min(100, (week.ready / goal) * 100)}%` }}
              />
            </div>
            <p className="mt-1.5 text-xs text-muted-foreground">
              {week.posted} posted
              {week.proposed > 0 && ` · ${week.proposed} waiting for approval`}
            </p>
          </li>
        )
      })}
    </ul>
  )
}

/** Weeks with nothing planned, and approved posts without a day that could fill them. */
function GapWarning({
  gaps,
  suggestions,
  onPlan,
}: {
  gaps: WeekCadence[]
  suggestions: Post[]
  onPlan: (post: Post, day: string) => void
}) {
  const first = gaps[0].start
  return (
    <div className="space-y-3 rounded-xl border border-tone-warning/30 bg-tone-warning-bg p-4 text-sm text-tone-warning">
      <p className="flex gap-2 font-medium">
        <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        Nothing planned for {gaps.map((week) => `the week of ${formatDay(week.start)}`).join(', ')}.
      </p>
      {suggestions.length > 0 ? (
        <Panel id="gap-suggestions-heading" title="Approved posts without a day" className="text-foreground">
          <RecordList label="Approved posts without a day">
            {suggestions.map((post) => (
              <li key={post.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <PostCover post={post} className="size-10" />
                <a href={paths.contentPost(post.id)} className="min-w-0 flex-1 font-medium hover:underline">
                  {stageLabels[post.stage]} · {formatLabels[post.format]} · {post.sessionTitle}
                </a>
                <Button type="button" size="sm" variant="outline" onClick={() => onPlan(post, first)}>
                  <CalendarClock aria-hidden="true" />
                  Plan for {formatDay(first)}
                </Button>
              </li>
            ))}
          </RecordList>
        </Panel>
      ) : (
        <p>
          Approve posts from a plan, or{' '}
          <a href={paths.content('review')} className="font-medium underline">
            review proposed plans
          </a>
          .
        </p>
      )}
    </div>
  )
}

function DayCell({
  day,
  today,
  compact,
  outside,
  posts,
}: {
  day: string
  today: boolean
  compact: boolean
  outside: boolean
  posts: Post[]
}) {
  const { setNodeRef, isOver } = useDroppable({ id: day })
  return (
    <section
      ref={setNodeRef}
      aria-label={formatDay(day)}
      className={cn(
        'flex min-w-0 flex-col gap-1.5 rounded-xl border bg-card p-2 shadow-xs',
        compact ? 'min-h-20' : 'min-h-28',
        outside && 'opacity-60',
        today && 'border-brand',
        isOver && 'ring-3 ring-ring/50',
      )}
    >
      <p className={cn('text-xs font-medium', today ? 'text-foreground' : 'text-muted-foreground')}>
        {dayLabel(day)}
        {today && <span className="sr-only"> (today)</span>}
      </p>
      {posts.length > 0 && (
        <ul className="flex flex-col gap-1.5">
          {posts.map((post) => (
            <PostChip key={post.id} post={post} compact={compact} />
          ))}
        </ul>
      )}
    </section>
  )
}

function PostChip({ post, compact }: { post: Post; compact: boolean }) {
  // Pointer and touch only: Enter keeps opening the post, whose page sets the day from the keyboard.
  const { listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: post.id,
    data: { post },
    disabled: post.status === 'POSTED',
  })
  const style = transform ? { transform: `translate3d(${transform.x}px, ${transform.y}px, 0)` } : undefined
  const label = `${stageLabels[post.stage]} · ${formatLabels[post.format]} · ${post.sessionTitle}`
  return (
    <li ref={setNodeRef} style={style} className={cn(isDragging && 'relative z-10 opacity-80')}>
      <a
        href={paths.contentPost(post.id)}
        title={label}
        aria-label={label}
        className={cn(
          'flex min-w-0 items-center gap-1.5 rounded-md border border-l-4 bg-background p-1 text-xs hover:bg-muted',
          statusBorders[post.status],
        )}
        {...listeners}
      >
        <PostCover post={post} className={compact ? 'size-6' : 'size-8'} />
        <span className={cn('min-w-0 truncate', compact && 'hidden lg:inline')}>
          {formatLabels[post.format]} · {post.sessionTitle}
        </span>
      </a>
    </li>
  )
}
