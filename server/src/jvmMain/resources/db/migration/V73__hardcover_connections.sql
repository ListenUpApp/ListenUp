-- One row per user connected to Hardcover (hardcover.app). Tokens are stored encrypted at rest
-- (AES-256-GCM, key derived from the JWT secret), so the database or a backup alone never exposes a
-- user's Hardcover account. Server-only; never synced. Deleting the user deletes the connection.
--
-- The FK lives here only: SQLDelight can't resolve cross-.sq references, so HardcoverConnections.sq
-- omits it.
CREATE TABLE hardcover_connections (
    user_id            TEXT    NOT NULL PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    hc_user_id         INTEGER NOT NULL,
    hc_username        TEXT    NOT NULL,
    access_token_enc   TEXT    NOT NULL,
    access_expires_at  INTEGER NOT NULL,
    refresh_token_enc  TEXT    NOT NULL,
    refresh_expires_at INTEGER NOT NULL,
    scopes             TEXT    NOT NULL,
    connected_at       INTEGER NOT NULL,
    broken_reason      TEXT
);
