import { WIDGET_TYPES, type DashboardWidget, type WidgetType } from '../api/dashboard.ts'
import type { WorkspaceRole } from '../api/workspaces.ts'

type WidgetInfo = {
  title: string
  /** Shown in the list of widgets to add. */
  description: string
  /** The width a newly added widget gets. */
  width: DashboardWidget['width']
  /** Only owners can add it; the server refuses it to members and leaves it out of their layouts. */
  ownerOnly?: boolean
}

export const widgetInfo: Record<WidgetType, WidgetInfo> = {
  AT_A_GLANCE: { title: 'At a glance', description: 'Counts of what needs attention.', width: 'FULL' },
  UPCOMING_SESSIONS: { title: 'Upcoming sessions', description: 'The next inquiries and bookings.', width: 'HALF' },
  MY_TASKS: { title: 'My tasks', description: 'Open tasks assigned to you, by due date.', width: 'HALF' },
  OPEN_INQUIRIES: { title: 'Open inquiries', description: 'Inquiries still to link or dismiss.', width: 'HALF' },
  CONTENT_TO_REVIEW: { title: 'Content to review', description: 'Batches with drafts not approved yet.', width: 'HALF' },
  GALLERIES: { title: 'Recent galleries', description: 'The newest galleries.', width: 'HALF' },
  RECENT_ACTIVITY: { title: 'Recent activity', description: 'The latest audited changes.', width: 'HALF', ownerOnly: true },
  QUICK_ACTIONS: { title: 'Quick actions', description: 'Shortcuts to create records.', width: 'HALF' },
}

/** The widgets the member could add: not on the dashboard yet and allowed for their role. */
export function addableWidgets(widgets: DashboardWidget[], role: WorkspaceRole): WidgetType[] {
  const shown = new Set(widgets.map((w) => w.type))
  return WIDGET_TYPES.filter((type) => !shown.has(type) && (role === 'OWNER' || !widgetInfo[type].ownerOnly))
}

export function addWidget(widgets: DashboardWidget[], type: WidgetType): DashboardWidget[] {
  return [...widgets, { type, width: widgetInfo[type].width }]
}

export function removeWidget(widgets: DashboardWidget[], type: WidgetType): DashboardWidget[] {
  return widgets.filter((w) => w.type !== type)
}

export function toggleWidth(widgets: DashboardWidget[], type: WidgetType): DashboardWidget[] {
  return widgets.map((w) => (w.type === type ? { ...w, width: w.width === 'FULL' ? 'HALF' : 'FULL' } : w))
}
