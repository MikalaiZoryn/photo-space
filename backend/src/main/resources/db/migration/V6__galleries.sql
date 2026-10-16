-- Private galleries, share links, and access events (Milestone 5). See ARCHITECTURE.md §6, §8 and D14.
--
-- A gallery is an ordered selection of one session's ready photos. Clients reach a published gallery only
-- through a share link, whose token is stored as a SHA-256 hash. Galleries never own photos: removing a photo
-- from a gallery, or archiving the gallery, leaves the photo and its files in place.

-- Targets of the composite foreign keys below, so a gallery photo is always a photo of the gallery's own
-- session and workspace.
ALTER TABLE photo_assets ADD CONSTRAINT photo_assets_workspace_session_id_uk UNIQUE (workspace_id, session_id, id);

CREATE TABLE galleries (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    session_id   UUID        NOT NULL,
    title        TEXT        NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    -- Share links work only while the gallery is PUBLISHED and not archived.
    status       TEXT        NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    -- What visitors may download: nothing, the 2048 px previews, or the originals.
    downloads    TEXT        NOT NULL CHECK (downloads IN ('NONE', 'PREVIEWS', 'ORIGINALS')),
    archived_at  TIMESTAMPTZ,
    version      INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT galleries_session_fk FOREIGN KEY (workspace_id, session_id) REFERENCES photo_sessions (workspace_id, id),
    CONSTRAINT galleries_workspace_id_uk UNIQUE (workspace_id, id),
    CONSTRAINT galleries_workspace_session_id_uk UNIQUE (workspace_id, session_id, id)
);

-- The gallery list, newest first, optionally for one session.
CREATE INDEX galleries_workspace_created_ix ON galleries (workspace_id, created_at DESC, id);
CREATE INDEX galleries_workspace_session_ix ON galleries (workspace_id, session_id);

-- A gallery's photos in display order. Deleting a photo (a later retention feature) removes it from galleries.
CREATE TABLE gallery_assets (
    gallery_id   UUID    NOT NULL,
    asset_id     UUID    NOT NULL,
    workspace_id UUID    NOT NULL,
    session_id   UUID    NOT NULL,
    position     INTEGER NOT NULL CHECK (position >= 0),
    PRIMARY KEY (gallery_id, asset_id),
    CONSTRAINT gallery_assets_position_uk UNIQUE (gallery_id, position),
    CONSTRAINT gallery_assets_gallery_fk FOREIGN KEY (workspace_id, session_id, gallery_id)
        REFERENCES galleries (workspace_id, session_id, id) ON DELETE CASCADE,
    CONSTRAINT gallery_assets_asset_fk FOREIGN KEY (workspace_id, session_id, asset_id)
        REFERENCES photo_assets (workspace_id, session_id, id) ON DELETE CASCADE
);

CREATE INDEX gallery_assets_asset_ix ON gallery_assets (asset_id);

-- A share link. Only the SHA-256 hash of its token is stored; the token itself is shown once, at creation.
-- Links are revoked, never deleted, so their access history stays readable.
CREATE TABLE gallery_share_links (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL,
    gallery_id   UUID        NOT NULL,
    token_hash   BYTEA       NOT NULL UNIQUE CHECK (length(token_hash) = 32),
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    created_by   UUID        NOT NULL REFERENCES users (id),
    revoked_by   UUID        REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gallery_share_links_gallery_fk FOREIGN KEY (workspace_id, gallery_id)
        REFERENCES galleries (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT gallery_share_links_workspace_id_uk UNIQUE (workspace_id, id),
    CONSTRAINT gallery_share_links_expiry_ck CHECK (expires_at > created_at),
    CONSTRAINT gallery_share_links_revoked_ck CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);

CREATE INDEX gallery_share_links_gallery_ix ON gallery_share_links (workspace_id, gallery_id, created_at DESC);

-- What visitors did through a link. No IP addresses, user agents, or other visitor data are recorded.
CREATE TABLE gallery_access_events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL,
    link_id      UUID        NOT NULL,
    kind         TEXT        NOT NULL CHECK (kind IN ('VIEW', 'DOWNLOAD')),
    -- The downloaded photo; NULL for views.
    asset_id     UUID,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gallery_access_events_link_fk FOREIGN KEY (workspace_id, link_id)
        REFERENCES gallery_share_links (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT gallery_access_events_asset_ck CHECK ((kind = 'DOWNLOAD') = (asset_id IS NOT NULL))
);

CREATE INDEX gallery_access_events_link_ix ON gallery_access_events (link_id, kind, occurred_at);
