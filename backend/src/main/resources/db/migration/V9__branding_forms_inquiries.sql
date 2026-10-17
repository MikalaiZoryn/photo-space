-- Branding, the public inquiry form, and inquiries (Milestone 9). See ARCHITECTURE.md §6 and D19.

-- Targets of the composite foreign keys below, so links stay inside one workspace.
ALTER TABLE photo_assets ADD CONSTRAINT photo_assets_workspace_id_uk UNIQUE (workspace_id, id);
ALTER TABLE tasks ADD CONSTRAINT tasks_workspace_id_uk UNIQUE (workspace_id, id);

-- Tasks created for an inquiry (its review task and its follow-up task).
ALTER TABLE tasks DROP CONSTRAINT tasks_source_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_source_check CHECK (source IN ('MANUAL', 'AUTOMATION', 'INQUIRY'));

-- Inquiry matching looks clients up by normalized email (trimmed, lower case).
CREATE INDEX clients_workspace_email_ix ON clients (workspace_id, lower(btrim(email))) WHERE email IS NOT NULL;

-- What visitors of the public inquiry pages see of the business. One row per workspace; a workspace without one
-- shows defaults. The logo is a photo asset without a session, uploaded and processed like a photo (D11).
CREATE TABLE workspace_branding (
    workspace_id  UUID PRIMARY KEY REFERENCES workspaces (id) ON DELETE CASCADE,
    display_name  TEXT        CHECK (length(display_name) BETWEEN 1 AND 100),
    logo_asset_id UUID,
    primary_color TEXT        NOT NULL CHECK (primary_color ~ '^#[0-9a-f]{6}$'),
    accent_color  TEXT        NOT NULL CHECK (accent_color ~ '^#[0-9a-f]{6}$'),
    font          TEXT        NOT NULL CHECK (font IN ('INTER', 'SYSTEM', 'SERIF', 'ROUNDED')),
    contact_email TEXT        CHECK (length(contact_email) BETWEEN 3 AND 320),
    contact_phone TEXT        CHECK (length(contact_phone) BETWEEN 3 AND 50),
    description   TEXT        CHECK (length(description) BETWEEN 1 AND 500),
    version       INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT workspace_branding_logo_fk FOREIGN KEY (workspace_id, logo_asset_id) REFERENCES photo_assets (workspace_id, id)
);

-- The workspace's General Inquiry form: one per workspace. `slug` is the opaque random part of its public URLs.
-- `draft_config` is what members edit; visitors only ever see a published version.
CREATE TABLE public_forms (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id         UUID        NOT NULL UNIQUE REFERENCES workspaces (id) ON DELETE CASCADE,
    slug                 TEXT        NOT NULL UNIQUE CHECK (slug ~ '^[A-Za-z0-9_-]{22}$'),
    status               TEXT        NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'UNPUBLISHED')),
    -- Validated by the application (FormConfig); the shape is defined per workspace by its questions.
    draft_config         JSONB       NOT NULL,
    published_version_id UUID,
    version              INTEGER     NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT public_forms_workspace_id_uk UNIQUE (workspace_id, id),
    CONSTRAINT public_forms_published_ck CHECK (status <> 'PUBLISHED' OR published_version_id IS NOT NULL)
);

-- Immutable snapshots of the draft, one per publish. Inquiries keep the version they were submitted against.
CREATE TABLE public_form_versions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    form_id      UUID        NOT NULL,
    number       INTEGER     NOT NULL CHECK (number >= 1),
    config       JSONB       NOT NULL,
    published_by UUID        REFERENCES users (id) ON DELETE SET NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT public_form_versions_number_uk UNIQUE (form_id, number),
    CONSTRAINT public_form_versions_workspace_form_id_uk UNIQUE (workspace_id, form_id, id),
    CONSTRAINT public_form_versions_form_fk FOREIGN KEY (workspace_id, form_id) REFERENCES public_forms (workspace_id, id)
);

ALTER TABLE public_forms ADD CONSTRAINT public_forms_published_version_fk
    FOREIGN KEY (workspace_id, id, published_version_id) REFERENCES public_form_versions (workspace_id, form_id, id);

-- One submission of the public form. It stays apart from clients until a member associates it with one. The
-- answers and contact fields are the original submission and never change (see the trigger below).
CREATE TABLE inquiries (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id      UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    form_id           UUID        NOT NULL,
    form_version_id   UUID        NOT NULL,
    -- RECEIVED until the matching job has run.
    status            TEXT        NOT NULL CHECK (status IN ('RECEIVED', 'NEW', 'NEEDS_REVIEW', 'ASSOCIATED', 'DISMISSED')),
    -- Question ID to answer, validated against the form version. Only answered questions are present.
    answers           JSONB       NOT NULL,
    contact_name      TEXT        NOT NULL CHECK (length(contact_name) BETWEEN 1 AND 200),
    contact_email     TEXT        NOT NULL CHECK (length(contact_email) BETWEEN 3 AND 320),
    email_normalized  TEXT        NOT NULL CHECK (email_normalized = lower(btrim(email_normalized))),
    contact_phone     TEXT        CHECK (length(contact_phone) BETWEEN 3 AND 50),
    service           TEXT        CHECK (length(service) BETWEEN 1 AND 100),
    preferred_date    DATE,
    source            TEXT        NOT NULL CHECK (source IN ('HOSTED', 'EMBED')),
    -- Origin and path of the page the visitor came from; never a query string.
    referrer          TEXT        CHECK (length(referrer) <= 500),
    utm_source        TEXT        CHECK (length(utm_source) <= 100),
    utm_medium        TEXT        CHECK (length(utm_medium) <= 100),
    utm_campaign      TEXT        CHECK (length(utm_campaign) <= 100),
    utm_term          TEXT        CHECK (length(utm_term) <= 100),
    utm_content       TEXT        CHECK (length(utm_content) <= 100),
    -- Generated by the visitor's browser for one form fill; a retried or double submit reuses it.
    idempotency_key   TEXT        NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9-]{16,64}$'),
    client_id         UUID,
    -- Whether association created the client from the answers (rather than linking an existing one).
    client_created    BOOLEAN     NOT NULL DEFAULT false,
    session_id        UUID,
    review_task_id    UUID,
    follow_up_task_id UUID,
    associated_by     UUID        REFERENCES users (id) ON DELETE SET NULL,
    associated_at     TIMESTAMPTZ,
    dismissed_by      UUID        REFERENCES users (id) ON DELETE SET NULL,
    dismissed_at      TIMESTAMPTZ,
    version           INTEGER     NOT NULL DEFAULT 0,
    submitted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT inquiries_workspace_id_uk UNIQUE (workspace_id, id),
    CONSTRAINT inquiries_idempotency_uk UNIQUE (form_id, idempotency_key),
    CONSTRAINT inquiries_form_version_fk FOREIGN KEY (workspace_id, form_id, form_version_id)
        REFERENCES public_form_versions (workspace_id, form_id, id),
    CONSTRAINT inquiries_client_fk FOREIGN KEY (workspace_id, client_id) REFERENCES clients (workspace_id, id),
    CONSTRAINT inquiries_session_fk FOREIGN KEY (workspace_id, session_id) REFERENCES photo_sessions (workspace_id, id),
    -- Tasks can be deleted; the inquiry then just loses the link.
    CONSTRAINT inquiries_review_task_fk FOREIGN KEY (workspace_id, review_task_id)
        REFERENCES tasks (workspace_id, id) ON DELETE SET NULL (review_task_id),
    CONSTRAINT inquiries_follow_up_task_fk FOREIGN KEY (workspace_id, follow_up_task_id)
        REFERENCES tasks (workspace_id, id) ON DELETE SET NULL (follow_up_task_id),
    CONSTRAINT inquiries_associated_ck CHECK ((status = 'ASSOCIATED') = (associated_at IS NOT NULL)
        AND (status <> 'ASSOCIATED' OR (client_id IS NOT NULL AND session_id IS NOT NULL))),
    CONSTRAINT inquiries_dismissed_ck CHECK ((status = 'DISMISSED') = (dismissed_at IS NOT NULL))
);

-- The inquiry list: one workspace, by status, newest first.
CREATE INDEX inquiries_workspace_status_ix ON inquiries (workspace_id, status, submitted_at DESC, id);

-- The original submission is kept as it arrived.
CREATE FUNCTION inquiries_keep_submission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.workspace_id, NEW.form_id, NEW.form_version_id, NEW.answers, NEW.contact_name, NEW.contact_email,
        NEW.email_normalized, NEW.contact_phone, NEW.service, NEW.preferred_date, NEW.source, NEW.referrer,
        NEW.utm_source, NEW.utm_medium, NEW.utm_campaign, NEW.utm_term, NEW.utm_content, NEW.idempotency_key,
        NEW.submitted_at)
       IS DISTINCT FROM
       (OLD.workspace_id, OLD.form_id, OLD.form_version_id, OLD.answers, OLD.contact_name, OLD.contact_email,
        OLD.email_normalized, OLD.contact_phone, OLD.service, OLD.preferred_date, OLD.source, OLD.referrer,
        OLD.utm_source, OLD.utm_medium, OLD.utm_campaign, OLD.utm_term, OLD.utm_content, OLD.idempotency_key,
        OLD.submitted_at) THEN
        RAISE EXCEPTION 'An inquiry''s submission cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER inquiries_keep_submission BEFORE UPDATE ON inquiries
    FOR EACH ROW EXECUTE FUNCTION inquiries_keep_submission();
