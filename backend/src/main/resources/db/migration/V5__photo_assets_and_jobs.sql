-- Photo assets and durable background jobs (Milestone 4). See ARCHITECTURE.md §6, §8, §9 and D11, D12.

-- Target of the composite foreign key below, so an asset can only belong to a session of its own workspace.
ALTER TABLE photo_sessions ADD CONSTRAINT photo_sessions_workspace_id_uk UNIQUE (workspace_id, id);

-- One uploaded photo. The original lives in private object storage under a server-generated key; derivatives
-- (thumbnail, preview) are written by the processing job. Only READY assets are shown as photos.
CREATE TABLE photo_assets (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id      UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    session_id        UUID,
    status            TEXT        NOT NULL
                          CHECK (status IN ('PENDING_UPLOAD', 'PROCESSING', 'READY', 'REJECTED', 'FAILED')),
    -- Display only, sanitized. Never used to build object keys.
    original_filename TEXT        NOT NULL CHECK (length(original_filename) BETWEEN 1 AND 255),
    -- Declared by the browser at upload time, then replaced by the type detected from the file's bytes.
    content_type      TEXT        NOT NULL CHECK (length(content_type) BETWEEN 1 AND 100),
    -- Declared size; the upload URL is signed for exactly this length and processing verifies it.
    byte_size         BIGINT      NOT NULL CHECK (byte_size > 0),
    original_key      TEXT        NOT NULL UNIQUE,
    thumbnail_key     TEXT,
    preview_key       TEXT,
    -- Set by processing.
    checksum_sha256   TEXT        CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    width             INTEGER     CHECK (width > 0),
    height            INTEGER     CHECK (height > 0),
    -- A fixed, user-facing sentence for REJECTED and FAILED assets. Never contains file content or errors.
    failure_reason    TEXT        CHECK (length(failure_reason) <= 300),
    uploaded_at       TIMESTAMPTZ,
    processed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A NULL session_id skips this check (MATCH SIMPLE); a set one must be a session in the same workspace.
    CONSTRAINT photo_assets_session_fk FOREIGN KEY (workspace_id, session_id) REFERENCES photo_sessions (workspace_id, id),
    CONSTRAINT photo_assets_ready_ck CHECK (status <> 'READY' OR (thumbnail_key IS NOT NULL AND preview_key IS NOT NULL
        AND checksum_sha256 IS NOT NULL AND width IS NOT NULL AND height IS NOT NULL)),
    CONSTRAINT photo_assets_failure_reason_ck CHECK ((status IN ('REJECTED', 'FAILED')) = (failure_reason IS NOT NULL))
);

-- A session's photos, oldest first (upload order).
CREATE INDEX photo_assets_workspace_session_ix ON photo_assets (workspace_id, session_id, created_at, id);
-- Cleanup of abandoned uploads.
CREATE INDEX photo_assets_pending_ix ON photo_assets (created_at) WHERE status = 'PENDING_UPLOAD';

-- Durable job records. A row is written in the same transaction as the change that needs the job (an outbox),
-- and the dispatcher hands due rows to the queue. The row, not the queue message, is the source of truth for
-- status, attempts, and the next attempt time.
CREATE TABLE background_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    job_type        TEXT        NOT NULL CHECK (length(job_type) BETWEEN 1 AND 100),
    subject_id      UUID        NOT NULL,
    -- Enqueueing the same key twice keeps the first job, so retried requests never duplicate work.
    idempotency_key TEXT        NOT NULL UNIQUE CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    status          TEXT        NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    attempts        INTEGER     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts    INTEGER     NOT NULL CHECK (max_attempts BETWEEN 1 AND 20),
    -- The earliest time the next attempt may start (retry backoff).
    run_after       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- When the job was last handed to the queue; NULL means it still has to be.
    dispatched_at   TIMESTAMPTZ,
    -- While RUNNING: the claiming worker's lease. A job whose lease ran out can be claimed again.
    locked_until    TIMESTAMPTZ,
    -- A safe summary (error class or fixed sentence). Never secrets, URLs, or payloads.
    last_error      TEXT        CHECK (length(last_error) <= 300),
    -- The request that caused the job, so worker logs can be matched to it.
    correlation_id  TEXT        CHECK (length(correlation_id) <= 64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);

-- The dispatcher's scan: unfinished jobs by due time.
CREATE INDEX background_jobs_due_ix ON background_jobs (run_after) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX background_jobs_subject_ix ON background_jobs (workspace_id, subject_id);
