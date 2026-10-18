package com.photospace.dashboard;

/**
 * A widget a member can place on their dashboard. Each one reads its data from another module's list API; this
 * module only stores where it goes. Adding one needs a migration for the {@code dashboard_widgets} check.
 */
enum DashboardWidget {
    /** Counts of upcoming sessions, open and overdue tasks, open inquiries, and content batches to review. */
    AT_A_GLANCE,
    UPCOMING_SESSIONS,
    /** Open tasks assigned to the viewer. */
    MY_TASKS,
    OPEN_INQUIRIES,
    CONTENT_TO_REVIEW,
    GALLERIES,
    /** The audit log, which only owners may read. */
    RECENT_ACTIVITY,
    QUICK_ACTIONS,
    /** The Content Studio posts planned or posted this week, against the weekly goal. */
    THIS_WEEK_POSTS;

    boolean ownerOnly() {
        return this == RECENT_ACTIVITY;
    }
}
