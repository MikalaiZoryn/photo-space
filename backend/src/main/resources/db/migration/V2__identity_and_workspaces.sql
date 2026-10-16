-- Identity (users, external identities) and tenancy (workspaces, memberships). See ARCHITECTURE.md §5–7.

CREATE TABLE users (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name TEXT        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A user signs in through an external identity. The stable key is (provider, subject), e.g. Google's `sub`.
-- The email is only a snapshot for display; it can change and is never used to look up a user.
CREATE TABLE external_identities (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider       TEXT        NOT NULL CHECK (provider IN ('google', 'dev')),
    subject        TEXT        NOT NULL CHECK (length(subject) BETWEEN 1 AND 255),
    email          TEXT,
    email_verified BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT external_identities_provider_subject_uk UNIQUE (provider, subject)
);

CREATE INDEX external_identities_user_id_ix ON external_identities (user_id);

CREATE TABLE workspaces (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    -- IANA zone id (e.g. Europe/Warsaw), used to display UTC timestamps.
    timezone   TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace_members (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         TEXT        NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT workspace_members_workspace_user_uk UNIQUE (workspace_id, user_id)
);

-- Membership lookups by user ("my workspaces"); (workspace_id, user_id) is covered by the unique constraint.
CREATE INDEX workspace_members_user_id_ix ON workspace_members (user_id);
