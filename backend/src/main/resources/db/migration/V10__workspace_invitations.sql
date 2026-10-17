-- Workspace invitations by email (D20). See ARCHITECTURE.md §6 and §7.

-- An owner invites an email address; whoever accepts must be signed in with an identity whose verified email is
-- that address. The membership is then keyed on the accepting user (Google `sub`), never on the email.
CREATE TABLE workspace_invitations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id       UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    -- Lower-cased. Compared with the accepting identity's verified email, case-insensitively.
    email              TEXT        NOT NULL CHECK (length(email) BETWEEN 3 AND 254 AND email = lower(email)),
    role               TEXT        NOT NULL CHECK (role = 'MEMBER'),
    status             TEXT        NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED')),
    -- SHA-256 of the token in the most recent email. The email job generates the token just before it sends, so
    -- the token itself is never stored; each send replaces the hash, so only the newest email's link works.
    token_hash         BYTEA       UNIQUE CHECK (length(token_hash) = 32),
    -- The token stops working at this time. Resending starts a new validity period.
    expires_at         TIMESTAMPTZ NOT NULL,
    email_status       TEXT        NOT NULL CHECK (email_status IN ('QUEUED', 'SENT', 'FAILED')),
    -- Counts sends, so each send's job has its own idempotency key.
    send_count         INTEGER     NOT NULL DEFAULT 1 CHECK (send_count >= 1),
    email_requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    email_sent_at      TIMESTAMPTZ,
    invited_by         UUID        NOT NULL REFERENCES users (id),
    accepted_by        UUID        REFERENCES users (id),
    accepted_at        TIMESTAMPTZ,
    revoked_by         UUID        REFERENCES users (id),
    revoked_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT workspace_invitations_accepted_ck
        CHECK ((status = 'ACCEPTED') = (accepted_by IS NOT NULL AND accepted_at IS NOT NULL)),
    CONSTRAINT workspace_invitations_revoked_ck
        CHECK ((status = 'REVOKED') = (revoked_by IS NOT NULL AND revoked_at IS NOT NULL))
);

-- One open invitation per address and workspace; to invite again, resend it. Also serves the pending list.
CREATE UNIQUE INDEX workspace_invitations_pending_uk
    ON workspace_invitations (workspace_id, email) WHERE status = 'PENDING';
