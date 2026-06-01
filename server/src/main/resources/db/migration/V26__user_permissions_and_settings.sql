-- Multi-user & admin substrate. Per-user permission flags gate whether a member
-- can edit metadata or share collections; sharing is the default, so existing
-- users keep both abilities (DEFAULT 1 backfills every row). Approval columns
-- record who admitted a user and when (for moderated registration); deleted_at
-- soft-deletes a user without orphaning their authored rows.
ALTER TABLE users ADD COLUMN can_edit INTEGER NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN can_share INTEGER NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN approved_by TEXT;
ALTER TABLE users ADD COLUMN approved_at INTEGER;
ALTER TABLE users ADD COLUMN deleted_at INTEGER;

-- Server-wide key/value settings. The table starts empty: an absent
-- registration_policy row means "use the startup default" (ServerSettingsRepository's
-- `default`, seeded from the `registration.policy` config), so the config stays
-- meaningful until an admin sets a policy at runtime, which then wins.
CREATE TABLE server_settings (
    key   TEXT NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (key)
);
