-- Baseline migration. It creates the Flyway history on a clean database and records
-- schema conventions. The first business tables (users, workspaces) arrive in Milestone 2.
--
-- Conventions for all later migrations (see ARCHITECTURE.md §6):
--   * UUID primary keys, generated with gen_random_uuid() (built into PostgreSQL 13+).
--   * Timestamps are TIMESTAMPTZ and stored in UTC.
--   * Every workspace-owned table has a NOT NULL workspace_id foreign key.
--   * JSONB is used only for variable provider metadata.
--   * Never edit an applied migration; add a new versioned file instead.

SELECT 1;
