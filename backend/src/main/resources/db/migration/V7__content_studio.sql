-- AI content studio (Milestone 6). See ARCHITECTURE.md §6, §10 and D16.
--
-- A brand profile per workspace. A generation batch is one request to the AI provider for selected photos of
-- one session; it produces caption drafts. Every edit of a draft is a new version, and approval names the
-- exact version that was approved. Nothing here is ever published automatically.

-- The photographer's voice and preferences, sent to the AI provider with every request.
CREATE TABLE brand_profiles (
    workspace_id UUID PRIMARY KEY REFERENCES workspaces (id) ON DELETE CASCADE,
    voice        TEXT CHECK (length(voice) <= 1000),
    audience     TEXT CHECK (length(audience) <= 1000),
    preferences  TEXT CHECK (length(preferences) <= 2000),
    -- Words, phrases, and topics the AI must not use.
    avoid        TEXT CHECK (length(avoid) <= 1000),
    version      INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE content_batches (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    session_id            UUID        NOT NULL,
    -- PENDING while queued, running, or waiting for a retry; then SUCCEEDED or FAILED.
    status                TEXT        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    -- The input as the photographer gave it. The session type is a snapshot taken at creation.
    session_type          TEXT,
    notes                 TEXT CHECK (length(notes) <= 4000),
    caption_count         INTEGER     NOT NULL CHECK (caption_count BETWEEN 1 AND 5),
    include_hashtags      BOOLEAN     NOT NULL,
    include_call_to_action BOOLEAN    NOT NULL,
    -- Counts runs the photographer started: 1, then one more per retry.
    run                   INTEGER     NOT NULL DEFAULT 1 CHECK (run >= 1),
    -- A fixed, user-facing sentence: why the batch failed, or the last problem while it is retried.
    failure_reason        TEXT,
    -- What produced the drafts. Never a credential.
    provider              TEXT,
    model                 TEXT,
    prompt_version        TEXT,
    input_tokens          INTEGER,
    output_tokens         INTEGER,
    -- Suggestions next to the captions.
    carousel_rationale    TEXT,
    story_ideas           TEXT[]      NOT NULL DEFAULT '{}',
    warnings              TEXT[]      NOT NULL DEFAULT '{}',
    created_by            UUID        NOT NULL REFERENCES users (id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at          TIMESTAMPTZ,
    CONSTRAINT content_batches_session_fk FOREIGN KEY (workspace_id, session_id)
        REFERENCES photo_sessions (workspace_id, id),
    CONSTRAINT content_batches_workspace_id_uk UNIQUE (workspace_id, id),
    CONSTRAINT content_batches_workspace_session_id_uk UNIQUE (workspace_id, session_id, id),
    CONSTRAINT content_batches_completed_ck CHECK ((status = 'PENDING') = (completed_at IS NULL))
);

CREATE INDEX content_batches_workspace_created_ix ON content_batches (workspace_id, created_at DESC, id);
CREATE INDEX content_batches_workspace_session_ix ON content_batches (workspace_id, session_id);

-- The photos of a batch, in the order they were selected, and the AI's suggested carousel order.
CREATE TABLE content_batch_assets (
    batch_id          UUID    NOT NULL,
    asset_id          UUID    NOT NULL,
    workspace_id      UUID    NOT NULL,
    session_id        UUID    NOT NULL,
    position          INTEGER NOT NULL CHECK (position >= 0),
    carousel_position INTEGER CHECK (carousel_position >= 0),
    PRIMARY KEY (batch_id, asset_id),
    CONSTRAINT content_batch_assets_position_uk UNIQUE (batch_id, position),
    CONSTRAINT content_batch_assets_batch_fk FOREIGN KEY (workspace_id, session_id, batch_id)
        REFERENCES content_batches (workspace_id, session_id, id) ON DELETE CASCADE,
    CONSTRAINT content_batch_assets_asset_fk FOREIGN KEY (workspace_id, session_id, asset_id)
        REFERENCES photo_assets (workspace_id, session_id, id) ON DELETE CASCADE
);

CREATE INDEX content_batch_assets_asset_ix ON content_batch_assets (asset_id);

-- One caption option. Its text lives in content_draft_versions; `version` is the latest one.
CREATE TABLE content_drafts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id     UUID        NOT NULL,
    batch_id         UUID        NOT NULL,
    position         INTEGER     NOT NULL CHECK (position >= 0),
    status           TEXT        NOT NULL CHECK (status IN ('DRAFT', 'APPROVED')),
    version          INTEGER     NOT NULL DEFAULT 1 CHECK (version >= 1),
    -- Approval applies to one version. Editing an approved draft makes it a draft again.
    approved_version INTEGER,
    approved_by      UUID REFERENCES users (id),
    approved_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT content_drafts_batch_fk FOREIGN KEY (workspace_id, batch_id)
        REFERENCES content_batches (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT content_drafts_workspace_id_uk UNIQUE (workspace_id, id),
    CONSTRAINT content_drafts_position_uk UNIQUE (batch_id, position),
    CONSTRAINT content_drafts_approval_ck CHECK (
        (status = 'APPROVED') = (approved_version IS NOT NULL)
        AND (approved_version IS NULL) = (approved_by IS NULL)
        AND (approved_version IS NULL) = (approved_at IS NULL)
        AND (approved_version IS NULL OR approved_version = version))
);

-- Every version of a draft: the generated one (version 1), then one per saved edit. Versions are never changed.
CREATE TABLE content_draft_versions (
    draft_id       UUID        NOT NULL,
    workspace_id   UUID        NOT NULL,
    version        INTEGER     NOT NULL CHECK (version >= 1),
    caption        TEXT        NOT NULL CHECK (length(caption) BETWEEN 1 AND 2200),
    call_to_action TEXT CHECK (length(call_to_action) <= 300),
    hashtags       TEXT[]      NOT NULL DEFAULT '{}',
    source         TEXT        NOT NULL CHECK (source IN ('GENERATED', 'EDITED')),
    -- The editor; NULL for generated text.
    created_by     UUID REFERENCES users (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (draft_id, version),
    CONSTRAINT content_draft_versions_draft_fk FOREIGN KEY (workspace_id, draft_id)
        REFERENCES content_drafts (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT content_draft_versions_source_ck CHECK ((source = 'GENERATED') = (created_by IS NULL))
);
