-- Tasks, deterministic automation rules, and the audit log (Milestone 7). See ARCHITECTURE.md §6 and D17.

-- One supported rule kind: when a session reaches trigger_status, create a task due due_in_days later.
-- Rules are disabled, never deleted, so runs keep their rule.
CREATE TABLE automation_rules (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id   UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    trigger_status TEXT        NOT NULL CHECK (trigger_status IN ('BOOKED', 'COMPLETED', 'DELIVERED', 'CANCELLED')),
    task_title     TEXT        NOT NULL CHECK (length(task_title) BETWEEN 1 AND 200),
    due_in_days    INTEGER     NOT NULL CHECK (due_in_days BETWEEN 0 AND 365),
    enabled        BOOLEAN     NOT NULL DEFAULT true,
    created_by     UUID        REFERENCES users (id) ON DELETE SET NULL,
    version        INTEGER     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT automation_rules_workspace_id_uk UNIQUE (workspace_id, id)
);

-- The enabled rules a status change fires.
CREATE INDEX automation_rules_trigger_ix ON automation_rules (workspace_id, trigger_status) WHERE enabled;

-- One run per rule and session: a rule fires at most once for a session, however often the event arrives.
-- The task title, due date, and client are fixed when the rule fires, so the job's result does not depend on
-- later edits.
CREATE TABLE automation_runs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id   UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    rule_id        UUID        NOT NULL,
    session_id     UUID        NOT NULL,
    client_id      UUID,
    trigger_status TEXT        NOT NULL CHECK (trigger_status IN ('BOOKED', 'COMPLETED', 'DELIVERED', 'CANCELLED')),
    task_title     TEXT        NOT NULL CHECK (length(task_title) BETWEEN 1 AND 200),
    due_date       DATE        NOT NULL,
    status         TEXT        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    -- How many times the run was started: 1, plus one per manual retry.
    run_count      INTEGER     NOT NULL DEFAULT 1 CHECK (run_count >= 1),
    -- A fixed, safe sentence; never an exception message.
    failure_reason TEXT        CHECK (length(failure_reason) <= 300),
    triggered_by   UUID        REFERENCES users (id) ON DELETE SET NULL,
    triggered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT automation_runs_workspace_id_uk UNIQUE (workspace_id, id),
    CONSTRAINT automation_runs_rule_session_uk UNIQUE (rule_id, session_id),
    CONSTRAINT automation_runs_rule_fk FOREIGN KEY (workspace_id, rule_id) REFERENCES automation_rules (workspace_id, id),
    CONSTRAINT automation_runs_session_fk FOREIGN KEY (workspace_id, session_id) REFERENCES photo_sessions (workspace_id, id),
    CONSTRAINT automation_runs_client_fk FOREIGN KEY (workspace_id, client_id) REFERENCES clients (workspace_id, id),
    CONSTRAINT automation_runs_finished_ck CHECK ((status = 'PENDING') = (finished_at IS NULL))
);

CREATE INDEX automation_runs_workspace_ix ON automation_runs (workspace_id, triggered_at DESC, id);

CREATE TABLE tasks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id      UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    client_id         UUID,
    session_id        UUID,
    title             TEXT        NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    description       TEXT        CHECK (length(description) <= 10000),
    -- A calendar date in the workspace time zone, not an instant.
    due_date          DATE,
    status            TEXT        NOT NULL CHECK (status IN ('OPEN', 'DONE')),
    assignee_id       UUID,
    source            TEXT        NOT NULL CHECK (source IN ('MANUAL', 'AUTOMATION')),
    -- The run that created the task. Unique, so a run creates at most one task even if its job runs twice.
    automation_run_id UUID        UNIQUE REFERENCES automation_runs (id) ON DELETE SET NULL,
    completed_at      TIMESTAMPTZ,
    completed_by      UUID        REFERENCES users (id) ON DELETE SET NULL,
    created_by        UUID        REFERENCES users (id) ON DELETE SET NULL,
    version           INTEGER     NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tasks_client_fk FOREIGN KEY (workspace_id, client_id) REFERENCES clients (workspace_id, id),
    CONSTRAINT tasks_session_fk FOREIGN KEY (workspace_id, session_id) REFERENCES photo_sessions (workspace_id, id),
    -- The assignee must be a member; removing the member unassigns the task.
    CONSTRAINT tasks_assignee_fk FOREIGN KEY (workspace_id, assignee_id)
        REFERENCES workspace_members (workspace_id, user_id) ON DELETE SET NULL (assignee_id),
    CONSTRAINT tasks_completed_ck CHECK ((status = 'DONE') = (completed_at IS NOT NULL))
);

-- The default task list: one workspace, by due date.
CREATE INDEX tasks_workspace_due_ix ON tasks (workspace_id, status, due_date NULLS LAST, created_at, id);
CREATE INDEX tasks_workspace_session_ix ON tasks (workspace_id, session_id) WHERE session_id IS NOT NULL;
CREATE INDEX tasks_workspace_client_ix ON tasks (workspace_id, client_id) WHERE client_id IS NOT NULL;

-- Who did what, when. Written in the transaction of the change itself. `detail` is a short, code-built text
-- (statuses, dates, counts), never user-entered text, tokens, or URLs. A NULL actor is the system (a rule).
CREATE TABLE audit_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id   UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    actor_id       UUID        REFERENCES users (id) ON DELETE SET NULL,
    action         TEXT        NOT NULL CHECK (length(action) BETWEEN 1 AND 100),
    target_type    TEXT        NOT NULL CHECK (length(target_type) BETWEEN 1 AND 50),
    target_id      UUID        NOT NULL,
    detail         TEXT        CHECK (length(detail) <= 300),
    correlation_id TEXT        CHECK (length(correlation_id) <= 100),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX audit_events_workspace_ix ON audit_events (workspace_id, created_at DESC, id);
CREATE INDEX audit_events_target_ix ON audit_events (workspace_id, target_id);
