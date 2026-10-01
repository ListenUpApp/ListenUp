-- Hardcover share mode (#1538). Server-only; a client sees it as HardcoverConnection.Connected.shareMode.
--
-- hardcover_preferences: each user's choices about Hardcover sync. share_mode is a HardcoverShareMode
--   name, AS_I_LISTEN or FINISHED_ONLY. No row means AS_I_LISTEN, so no existing user's behaviour changes.
--   A table of its own because reconnecting replaces the hardcover_connections row (INSERT OR REPLACE),
--   which would reset the choice; and a disconnect keeps it — it is the listener's choice, not the
--   connection's. Deleting the user deletes it.
--
-- The FK lives here only: SQLDelight can't resolve cross-.sq references, so the .sq file omits it.

CREATE TABLE hardcover_preferences (
    user_id    TEXT    NOT NULL PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    share_mode TEXT    NOT NULL,
    updated_at INTEGER NOT NULL
);
