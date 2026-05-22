-- Playback P1: per-user playback position table.
-- One row per (user_id, book_id) pair — the current resume point for a user's
-- progress through a book. No backfill — new table.

CREATE TABLE playback_positions (
    id               TEXT    NOT NULL,
    user_id          TEXT    NOT NULL,
    book_id          TEXT    NOT NULL,
    position_ms      INTEGER NOT NULL,
    last_played_at   INTEGER NOT NULL,
    finished         INTEGER NOT NULL DEFAULT 0,
    playback_speed   REAL    NOT NULL DEFAULT 1.0,
    current_chapter_id TEXT  NULL,
    revision         INTEGER NOT NULL DEFAULT 0,
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL,
    deleted_at       INTEGER NULL,
    client_op_id     TEXT    NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_playback_position_user_book ON playback_positions(user_id, book_id);
CREATE        INDEX idx_playback_position_user_id   ON playback_positions(user_id);
CREATE        INDEX idx_playback_position_revision  ON playback_positions(revision);
CREATE        INDEX idx_playback_position_user_rev  ON playback_positions(user_id, revision);
