-- Playback P3: per-user active listening sessions.
-- One row per user while they are actively listening to a book. The completion
-- cascade in PlaybackPositionRepository hard-deletes the row when finished flips
-- false→true. ActiveSessionCleanupTask evicts orphan rows after 30 minutes of
-- staleness (ungraceful disconnects, OS kills, lost SSE events).
-- No backfill — new table.

CREATE TABLE active_sessions (
    session_id   TEXT    NOT NULL,
    user_id      TEXT    NOT NULL,
    book_id      TEXT    NOT NULL,
    started_at   INTEGER NOT NULL,
    revision     INTEGER NOT NULL DEFAULT 0,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    deleted_at   INTEGER NULL,
    client_op_id TEXT    NULL,
    PRIMARY KEY (session_id)
);

CREATE INDEX idx_active_sessions_user_id   ON active_sessions(user_id);
CREATE INDEX idx_active_sessions_revision  ON active_sessions(revision);
CREATE INDEX idx_active_sessions_user_rev  ON active_sessions(user_id, revision);
CREATE INDEX idx_active_sessions_updated   ON active_sessions(updated_at);
