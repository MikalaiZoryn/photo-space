-- Content Studio: session plans, calendar, and post kit (Milestone 13, D22). See ARCHITECTURE.md §6, §10.
--
-- A plan turns one session into a run of posts: a sneak peek, delivery posts, and drip posts. The AI proposes the
-- posts in a PLAN batch; each post's text is an ordinary content draft, so editing is versioned and approval names
-- one version. Dates are set by code, never by the model. Nothing here is ever posted automatically: "posted" is
-- set by a person.

-- Content Studio settings that persist across sessions. A workspace without a row reads the defaults at version 0.
CREATE TABLE content_studio_settings (
    workspace_id      UUID PRIMARY KEY REFERENCES workspaces (id) ON DELETE CASCADE,
    -- Feed posts per week the calendar's cadence meter aims for.
    weekly_goal       INTEGER     NOT NULL DEFAULT 4 CHECK (weekly_goal BETWEEN 1 AND 14),
    -- Propose a plan by itself once a session's photos are ready.
    auto_plans        BOOLEAN     NOT NULL DEFAULT true,
    -- The plan template: days from the plan to its sneak peek, and how many drip posts follow the gallery, how far apart.
    sneak_peek_days   INTEGER     NOT NULL DEFAULT 1 CHECK (sneak_peek_days BETWEEN 0 AND 3),
    drip_posts        INTEGER     NOT NULL DEFAULT 6 CHECK (drip_posts BETWEEN 0 AND 8),
    drip_spacing_days INTEGER     NOT NULL DEFAULT 7 CHECK (drip_spacing_days BETWEEN 1 AND 30),
    -- Where the studio works, such as "Kraków". Captions are checked for it, and the AI may use it.
    studio_area       TEXT CHECK (length(studio_area) BETWEEN 1 AND 100),
    version           INTEGER     NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A batch now writes either caption options (Milestone 6) or a session plan. A plan asks for up to 20 posts.
ALTER TABLE content_batches
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'CAPTIONS' CHECK (kind IN ('CAPTIONS', 'PLAN'));
ALTER TABLE content_batches DROP CONSTRAINT content_batches_caption_count_check;
ALTER TABLE content_batches ADD CONSTRAINT content_batches_caption_count_check CHECK (caption_count BETWEEN 1 AND 20);
-- A plan proposed automatically has no requester.
ALTER TABLE content_batches ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE content_batches ADD CONSTRAINT content_batches_created_by_ck CHECK (kind = 'PLAN' OR created_by IS NOT NULL);

-- One plan per session. batch_id is its latest PLAN batch: set in the transaction that creates the plan, and moved
-- on by "Refresh plan".
CREATE TABLE content_plans (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    session_id   UUID        NOT NULL,
    origin       TEXT        NOT NULL CHECK (origin IN ('AUTOMATIC', 'MANUAL')),
    batch_id     UUID,
    -- The workspace-local day the session's gallery was first published. Delivery and drip dates count from it.
    published_on DATE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT content_plans_session_fk FOREIGN KEY (workspace_id, session_id)
        REFERENCES photo_sessions (workspace_id, id),
    CONSTRAINT content_plans_batch_fk FOREIGN KEY (workspace_id, session_id, batch_id)
        REFERENCES content_batches (workspace_id, session_id, id),
    CONSTRAINT content_plans_session_uk UNIQUE (workspace_id, session_id),
    CONSTRAINT content_plans_workspace_id_uk UNIQUE (workspace_id, id),
    CONSTRAINT content_plans_workspace_session_id_uk UNIQUE (workspace_id, session_id, id)
);

-- One planned post. Its caption, call to action, and hashtags are the draft's current version; it is approved when
-- the draft is.
CREATE TABLE content_posts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL,
    session_id   UUID        NOT NULL,
    plan_id      UUID        NOT NULL,
    draft_id     UUID        NOT NULL,
    stage        TEXT        NOT NULL CHECK (stage IN ('SNEAK_PEEK', 'DELIVERY', 'DRIP')),
    -- SINGLE, CAROUSEL, and REEL count toward the weekly goal; a STORY does not.
    format       TEXT        NOT NULL CHECK (format IN ('SINGLE', 'CAROUSEL', 'REEL', 'STORY')),
    -- Notes for a Reel or Story: frame order, cover, on-screen text, audio mood, stickers.
    idea         TEXT CHECK (length(idea) <= 1000),
    -- Days after the anchor: the plan's creation for a sneak peek, the gallery's first publication otherwise.
    offset_days  INTEGER     NOT NULL CHECK (offset_days BETWEEN 0 AND 365),
    -- The workspace-local day to post. NULL until the anchor is known or someone picks a day.
    planned_on   DATE,
    -- Set by a person after posting by hand.
    posted_on    DATE,
    post_url     TEXT CHECK (length(post_url) <= 500),
    posted_by    UUID REFERENCES users (id),
    -- A person changed its photos, day, or caption. "Refresh plan" replaces only untouched, unapproved posts.
    touched      BOOLEAN     NOT NULL DEFAULT false,
    -- A one-post caption rewrite is queued; the job clears it.
    regenerating BOOLEAN     NOT NULL DEFAULT false,
    -- A fixed, user-facing sentence when the last rewrite failed.
    failure_reason TEXT,
    version      INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT content_posts_plan_fk FOREIGN KEY (workspace_id, session_id, plan_id)
        REFERENCES content_plans (workspace_id, session_id, id) ON DELETE CASCADE,
    CONSTRAINT content_posts_draft_fk FOREIGN KEY (workspace_id, draft_id)
        REFERENCES content_drafts (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT content_posts_draft_uk UNIQUE (draft_id),
    CONSTRAINT content_posts_workspace_id_uk UNIQUE (workspace_id, id),
    CONSTRAINT content_posts_workspace_session_id_uk UNIQUE (workspace_id, session_id, id),
    CONSTRAINT content_posts_posted_ck CHECK ((posted_on IS NULL) = (posted_by IS NULL)
        AND (post_url IS NULL OR posted_on IS NOT NULL))
);

-- The calendar reads posts by planned or posted day.
CREATE INDEX content_posts_workspace_planned_ix ON content_posts (workspace_id, planned_on);
CREATE INDEX content_posts_workspace_posted_ix ON content_posts (workspace_id, posted_on);
CREATE INDEX content_posts_plan_ix ON content_posts (plan_id);

-- The photos of a post in posting order, each with its 4:5 crop as fractions of the upright photo. A NULL crop is
-- the centred default. Originals are never changed; the post kit renders the crop as a separate file.
CREATE TABLE content_post_photos (
    post_id      UUID             NOT NULL,
    asset_id     UUID             NOT NULL,
    workspace_id UUID             NOT NULL,
    session_id   UUID             NOT NULL,
    position     INTEGER          NOT NULL CHECK (position >= 0),
    crop_x       DOUBLE PRECISION,
    crop_y       DOUBLE PRECISION,
    crop_width   DOUBLE PRECISION,
    crop_height  DOUBLE PRECISION,
    PRIMARY KEY (post_id, asset_id),
    CONSTRAINT content_post_photos_position_uk UNIQUE (post_id, position),
    CONSTRAINT content_post_photos_post_fk FOREIGN KEY (workspace_id, session_id, post_id)
        REFERENCES content_posts (workspace_id, session_id, id) ON DELETE CASCADE,
    CONSTRAINT content_post_photos_asset_fk FOREIGN KEY (workspace_id, session_id, asset_id)
        REFERENCES photo_assets (workspace_id, session_id, id) ON DELETE CASCADE,
    CONSTRAINT content_post_photos_crop_ck CHECK (
        (crop_x IS NULL AND crop_y IS NULL AND crop_width IS NULL AND crop_height IS NULL)
        OR (crop_x >= 0 AND crop_y >= 0 AND crop_width > 0 AND crop_height > 0
            AND crop_x + crop_width <= 1.000001 AND crop_y + crop_height <= 1.000001))
);

CREATE INDEX content_post_photos_asset_ix ON content_post_photos (asset_id);

-- The "This week's posts" dashboard widget.
ALTER TABLE dashboard_widgets DROP CONSTRAINT dashboard_widgets_widget_check;
ALTER TABLE dashboard_widgets ADD CONSTRAINT dashboard_widgets_widget_check
    CHECK (widget IN ('AT_A_GLANCE', 'UPCOMING_SESSIONS', 'MY_TASKS', 'OPEN_INQUIRIES', 'CONTENT_TO_REVIEW',
                      'GALLERIES', 'RECENT_ACTIVITY', 'QUICK_ACTIONS', 'THIS_WEEK_POSTS'));
