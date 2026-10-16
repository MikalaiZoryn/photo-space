-- Clients and photography sessions (Milestone 3). See ARCHITECTURE.md §6.
--
-- Both are archived, never deleted, by the application: archived_at hides a record from default lists but
-- keeps it and its links. `version` supports optimistic locking of concurrent edits.

CREATE TABLE clients (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name         TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    email        TEXT        CHECK (length(email) BETWEEN 3 AND 320),
    phone        TEXT        CHECK (length(phone) BETWEEN 3 AND 50),
    notes        TEXT        CHECK (length(notes) <= 10000),
    archived_at  TIMESTAMPTZ,
    version      INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Target of the composite foreign key below, so a session can only link to a client of its own workspace.
    CONSTRAINT clients_workspace_id_uk UNIQUE (workspace_id, id)
);

-- The default client list: one workspace, sorted by name.
CREATE INDEX clients_workspace_name_ix ON clients (workspace_id, lower(name), id);

CREATE TABLE photo_sessions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    client_id    UUID,
    title        TEXT        NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    session_type TEXT        CHECK (length(session_type) BETWEEN 1 AND 100),
    status       TEXT        NOT NULL CHECK (status IN ('INQUIRY', 'BOOKED', 'COMPLETED', 'DELIVERED', 'CANCELLED')),
    -- UTC instants. The UI enters and shows them in the workspace time zone.
    starts_at    TIMESTAMPTZ,
    ends_at      TIMESTAMPTZ,
    location     TEXT        CHECK (length(location) BETWEEN 1 AND 300),
    notes        TEXT        CHECK (length(notes) <= 10000),
    archived_at  TIMESTAMPTZ,
    version      INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A NULL client_id skips this check (MATCH SIMPLE); a set one must be a client in the same workspace.
    CONSTRAINT photo_sessions_client_fk FOREIGN KEY (workspace_id, client_id) REFERENCES clients (workspace_id, id),
    CONSTRAINT photo_sessions_end_after_start_ck CHECK (ends_at IS NULL OR (starts_at IS NOT NULL AND ends_at > starts_at)),
    -- Once booked, a session has a date.
    CONSTRAINT photo_sessions_scheduled_when_booked_ck CHECK (starts_at IS NOT NULL OR status IN ('INQUIRY', 'CANCELLED'))
);

-- The default session list: one workspace, newest first, unscheduled at the top.
CREATE INDEX photo_sessions_workspace_start_ix ON photo_sessions (workspace_id, starts_at DESC NULLS FIRST, created_at DESC, id);
-- Sessions of one client.
CREATE INDEX photo_sessions_workspace_client_ix ON photo_sessions (workspace_id, client_id) WHERE client_id IS NOT NULL;
