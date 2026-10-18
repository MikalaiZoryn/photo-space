import { api } from './client.ts'

export const WIDGET_TYPES = [
  'AT_A_GLANCE',
  'UPCOMING_SESSIONS',
  'MY_TASKS',
  'OPEN_INQUIRIES',
  'CONTENT_TO_REVIEW',
  'GALLERIES',
  'RECENT_ACTIVITY',
  'QUICK_ACTIONS',
  'THIS_WEEK_POSTS',
] as const
export type WidgetType = (typeof WIDGET_TYPES)[number]

/** A share of the two-column grid. Phones show one column, so both are full width there. */
export type WidgetWidth = 'HALF' | 'FULL'

export type DashboardWidget = { type: WidgetType; width: WidgetWidth }

/** The signed-in member's own layout. `customized` is false while it is the default for their role (version 0). */
export type DashboardLayout = {
  widgets: DashboardWidget[]
  customized: boolean
  version: number
  updatedAt: string | null
}

function layoutPath(workspaceId: string) {
  return `/api/workspaces/${encodeURIComponent(workspaceId)}/dashboard/layout`
}

export function getDashboardLayout(workspaceId: string, signal?: AbortSignal): Promise<DashboardLayout> {
  return api<DashboardLayout>(layoutPath(workspaceId), { signal })
}

/** Replaces the whole layout; the order of `widgets` is the reading order. */
export function saveDashboardLayout(
  workspaceId: string,
  version: number,
  widgets: DashboardWidget[],
): Promise<DashboardLayout> {
  return api<DashboardLayout>(layoutPath(workspaceId), { method: 'PUT', body: { version, widgets } })
}

/** Back to the default layout, which the response holds. */
export function resetDashboardLayout(workspaceId: string): Promise<DashboardLayout> {
  return api<DashboardLayout>(layoutPath(workspaceId), { method: 'DELETE' })
}
