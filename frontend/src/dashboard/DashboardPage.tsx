import { useId, useState, type ComponentType, type ReactNode } from 'react'
import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type Announcements,
  type DragEndEvent,
  type UniqueIdentifier,
} from '@dnd-kit/core'
import {
  arrayMove,
  rectSortingStrategy,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { ChevronRight, GripVertical, Maximize2, Minimize2, Plus, X } from 'lucide-react'
import { cn } from 'cn'
import { Button } from '@/components/ui/button'
import {
  getDashboardLayout,
  resetDashboardLayout,
  saveDashboardLayout,
  type DashboardWidget,
  type WidgetType,
} from '../api/dashboard.ts'
import type { Workspace } from '../api/workspaces.ts'
import { paths } from '../routing.ts'
import { EmptyState, ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { toFormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { useLoad } from '../useLoad.ts'
import { addableWidgets, addWidget, removeWidget, toggleWidth, widgetInfo } from './catalog.ts'
import {
  AtAGlance,
  ContentToReview,
  MyTasks,
  OpenInquiries,
  QuickActions,
  RecentActivity,
  RecentGalleries,
  ThisWeekPosts,
  UpcomingSessions,
  type WidgetProps,
} from './Widgets.tsx'

const widgetComponents: Record<WidgetType, ComponentType<WidgetProps>> = {
  AT_A_GLANCE: AtAGlance,
  UPCOMING_SESSIONS: UpcomingSessions,
  MY_TASKS: MyTasks,
  OPEN_INQUIRIES: OpenInquiries,
  CONTENT_TO_REVIEW: ContentToReview,
  GALLERIES: RecentGalleries,
  RECENT_ACTIVITY: RecentActivity,
  QUICK_ACTIONS: QuickActions,
  THIS_WEEK_POSTS: ThisWeekPosts,
}

/** Where a widget's "View all" link goes, if it has a list page. */
const viewAll: Partial<Record<WidgetType, string>> = {
  UPCOMING_SESSIONS: paths.sessions(),
  MY_TASKS: paths.tasks(),
  OPEN_INQUIRIES: paths.inquiries(),
  CONTENT_TO_REVIEW: paths.content(),
  THIS_WEEK_POSTS: paths.content(),
  GALLERIES: paths.galleries(),
  RECENT_ACTIVITY: paths.activity(),
}

type Props = { workspace: Workspace; userId: string }

/**
 * The member's own dashboard: the widgets they chose, in their order, on a two-column grid. Customizing edits a
 * draft of the layout (drag a widget by its handle, change its width, remove or add widgets) that Save stores on
 * the server. The widgets stay mounted while customizing, so their data is not loaded again.
 */
export function DashboardPage({ workspace, userId }: Props) {
  const layout = useLoad((signal) => getDashboardLayout(workspace.id, signal), [workspace.id])
  const [draft, setDraft] = useState<DashboardWidget[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (layout.state.kind === 'loading') return <Loading>Loading your dashboard…</Loading>
  if (layout.state.kind === 'error') {
    return <LoadError error={layout.state.error} what="dashboard" onRetry={layout.reload} />
  }
  const saved = layout.state.value
  const editing = draft !== null
  const widgets = draft ?? saved.widgets

  async function run(action: () => Promise<void>, what: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(toFormError(e, what).message ?? `Could not ${what}.`)
    } finally {
      setBusy(false)
    }
  }

  const save = () =>
    run(async () => {
      layout.replace(await saveDashboardLayout(workspace.id, saved.version, widgets))
      setDraft(null)
    }, 'save the dashboard')

  const reset = () =>
    run(async () => {
      layout.replace(await resetDashboardLayout(workspace.id))
      setDraft(null)
    }, 'reset the dashboard')

  function cancel() {
    setDraft(null)
    setError(null)
  }

  const actions = editing ? (
    <>
      <Button type="button" variant="ghost" disabled={busy} onClick={reset}>
        Reset to default
      </Button>
      <Button type="button" variant="outline" disabled={busy} onClick={cancel}>
        Cancel
      </Button>
      <Button type="button" disabled={busy} onClick={save}>
        {busy ? 'Saving…' : 'Save'}
      </Button>
    </>
  ) : (
    <Button type="button" variant="outline" onClick={() => setDraft(saved.widgets)}>
      Customize
    </Button>
  )

  return (
    <section aria-labelledby="dashboard-heading">
      <PageHeader
        id="dashboard-heading"
        title="Dashboard"
        description={editing ? 'Drag widgets by their handle to rearrange them, then save.' : undefined}
        actions={actions}
      />
      {error && (
        <div className="mb-4">
          <ErrorAlert>{error}</ErrorAlert>
        </div>
      )}
      {editing && <AddWidgets widgets={widgets} role={workspace.role} onAdd={(type) => setDraft(addWidget(widgets, type))} />}
      {widgets.length === 0 ? (
        <EmptyState
          action={
            !editing && (
              <Button type="button" variant="outline" onClick={() => setDraft(saved.widgets)}>
                Customize
              </Button>
            )
          }
        >
          {editing ? 'Add a widget above.' : 'Your dashboard has no widgets.'}
        </EmptyState>
      ) : (
        <WidgetGrid widgets={widgets} editing={editing} onChange={setDraft}>
          {(widget) => {
            const Widget = widgetComponents[widget.type]
            return <Widget workspaceId={workspace.id} timezone={workspace.timezone} userId={userId} />
          }}
        </WidgetGrid>
      )}
    </section>
  )
}

function AddWidgets({
  widgets,
  role,
  onAdd,
}: {
  widgets: DashboardWidget[]
  role: Workspace['role']
  onAdd: (type: WidgetType) => void
}) {
  const addable = addableWidgets(widgets, role)
  return (
    <section aria-labelledby="add-widgets-heading" className="mb-6 rounded-xl border border-dashed bg-card/50 p-4">
      <h4 id="add-widgets-heading" className="mb-3 text-sm font-semibold">
        Add widgets
      </h4>
      {addable.length === 0 ? (
        <p className="text-sm text-muted-foreground">Every widget is on your dashboard.</p>
      ) : (
        <ul className="flex flex-wrap gap-2">
          {addable.map((type) => (
            <li key={type}>
              <Button type="button" variant="outline" size="sm" title={widgetInfo[type].description} onClick={() => onAdd(type)}>
                <Plus aria-hidden="true" />
                {widgetInfo[type].title}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function WidgetGrid({
  widgets,
  editing,
  onChange,
  children,
}: {
  widgets: DashboardWidget[]
  editing: boolean
  onChange: (widgets: DashboardWidget[]) => void
  children: (widget: DashboardWidget) => ReactNode
}) {
  const sensors = useSensors(
    // A short drag distance keeps clicks on the handle from starting a drag.
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )
  const ids = widgets.map((w) => w.type)

  function dragEnd({ active, over }: DragEndEvent) {
    if (!over || active.id === over.id) return
    onChange(arrayMove(widgets, ids.indexOf(active.id as WidgetType), ids.indexOf(over.id as WidgetType)))
  }

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragEnd={dragEnd}
      accessibility={{ announcements: announcements(ids), screenReaderInstructions }}
    >
      <SortableContext items={ids} strategy={rectSortingStrategy} disabled={!editing}>
        <ul aria-label="Widgets" className="grid gap-4 md:grid-cols-2">
          {widgets.map((widget) => (
            <SortableWidget
              key={widget.type}
              widget={widget}
              editing={editing}
              onToggleWidth={() => onChange(toggleWidth(widgets, widget.type))}
              onRemove={() => onChange(removeWidget(widgets, widget.type))}
            >
              {children(widget)}
            </SortableWidget>
          ))}
        </ul>
      </SortableContext>
    </DndContext>
  )
}

function SortableWidget({
  widget,
  editing,
  onToggleWidth,
  onRemove,
  children,
}: {
  widget: DashboardWidget
  editing: boolean
  onToggleWidth: () => void
  onRemove: () => void
  children: ReactNode
}) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } = useSortable({
    id: widget.type,
  })
  const headingId = useId()
  const { title } = widgetInfo[widget.type]
  const full = widget.width === 'FULL'
  const href = viewAll[widget.type]

  return (
    <li
      ref={setNodeRef}
      // Translate only: widgets of different widths must not be stretched into each other's shape.
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={cn(full && 'md:col-span-2', isDragging && 'relative z-10')}
    >
      <section
        aria-labelledby={headingId}
        className={cn(
          'flex h-full flex-col overflow-hidden rounded-xl border bg-card text-card-foreground shadow-xs',
          editing && 'ring-1 ring-border',
          isDragging && 'shadow-lg ring-2 ring-ring/50',
        )}
      >
        <div className="flex min-h-12 items-center gap-2 border-b px-4 py-2">
          {editing && (
            <Button
              ref={setActivatorNodeRef}
              type="button"
              variant="ghost"
              size="icon-sm"
              className="-ml-2 cursor-grab touch-none active:cursor-grabbing"
              aria-label={`Move ${title}`}
              {...attributes}
              {...listeners}
            >
              <GripVertical aria-hidden="true" />
            </Button>
          )}
          <h4 id={headingId} className="min-w-0 flex-1 truncate text-base font-semibold">
            {title}
          </h4>
          {editing ? (
            <div className="-mr-2 flex gap-1">
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                className="hidden md:inline-flex"
                aria-label={full ? `Make ${title} half width` : `Make ${title} full width`}
                title={full ? 'Half width' : 'Full width'}
                onClick={onToggleWidth}
              >
                {full ? <Minimize2 aria-hidden="true" /> : <Maximize2 aria-hidden="true" />}
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={`Remove ${title}`}
                title="Remove"
                onClick={onRemove}
              >
                <X aria-hidden="true" />
              </Button>
            </div>
          ) : (
            href && (
              <a
                href={href}
                aria-label={`View all: ${title}`}
                className="-mr-1 inline-flex items-center gap-0.5 text-sm font-medium text-muted-foreground hover:text-foreground"
              >
                View all
                <ChevronRight className="size-4" aria-hidden="true" />
              </a>
            )
          )}
        </div>
        <div className="flex-1">{children}</div>
      </section>
    </li>
  )
}

const screenReaderInstructions = {
  draggable:
    'To move a widget, press Space or Enter. Use the arrow keys to move it, then press Space or Enter to drop it ' +
    'there, or Escape to cancel.',
}

/** Screen reader messages that name the widgets and their places instead of their IDs. */
function announcements(ids: WidgetType[]): Announcements {
  const title = (id: UniqueIdentifier) => widgetInfo[id as WidgetType]?.title ?? String(id)
  const place = (id: UniqueIdentifier) => `position ${ids.indexOf(id as WidgetType) + 1} of ${ids.length}`
  return {
    onDragStart: ({ active }) => `Picked up ${title(active.id)}, at ${place(active.id)}.`,
    onDragOver: ({ active, over }) => (over ? `${title(active.id)} is over ${place(over.id)}.` : undefined),
    onDragEnd: ({ active, over }) =>
      over ? `${title(active.id)} was dropped at ${place(over.id)}.` : `${title(active.id)} was dropped.`,
    onDragCancel: ({ active }) => `Moving ${title(active.id)} was cancelled.`,
  }
}
