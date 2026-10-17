-- Customizable dashboards (D21). See ARCHITECTURE.md §6.

-- One saved layout per member and workspace. A member without a row sees the default layout for their role; the
-- row also tells "saved with no widgets" apart from "never customized".
CREATE TABLE dashboard_layouts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    version      INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT dashboard_layouts_member_uk UNIQUE (workspace_id, user_id)
);

-- The widgets shown, in reading order: left to right, then top to bottom on a two-column grid.
CREATE TABLE dashboard_widgets (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID    NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    layout_id    UUID    NOT NULL REFERENCES dashboard_layouts (id) ON DELETE CASCADE,
    widget       TEXT    NOT NULL CHECK (widget IN ('AT_A_GLANCE', 'UPCOMING_SESSIONS', 'MY_TASKS', 'OPEN_INQUIRIES',
                                                    'CONTENT_TO_REVIEW', 'GALLERIES', 'RECENT_ACTIVITY',
                                                    'QUICK_ACTIONS')),
    position     INTEGER NOT NULL CHECK (position >= 0),
    width        TEXT    NOT NULL CHECK (width IN ('HALF', 'FULL')),
    CONSTRAINT dashboard_widgets_widget_uk UNIQUE (layout_id, widget),
    CONSTRAINT dashboard_widgets_position_uk UNIQUE (layout_id, position)
);
