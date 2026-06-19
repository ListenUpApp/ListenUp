-- Playback P2: per-user append-only listening spans and materialized stats.
-- No backfill — new tables.

-- listening_events: one row per closed playback span recorded by a client.
-- Append-only: a span is never mutated after creation. Durations are derived
-- from start/end positions and start/end wall-times; never stored.
CREATE TABLE listening_events (
    id                  TEXT    NOT NULL,
    user_id             TEXT    NOT NULL,
    book_id             TEXT    NOT NULL,
    start_position_ms   INTEGER NOT NULL,
    end_position_ms     INTEGER NOT NULL,
    started_at          INTEGER NOT NULL,
    ended_at            INTEGER NOT NULL,
    playback_speed      REAL    NOT NULL,
    tz                  TEXT    NOT NULL,
    device_label        TEXT    NULL,
    revision            INTEGER NOT NULL DEFAULT 0,
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER NOT NULL,
    deleted_at          INTEGER NULL,
    client_op_id        TEXT    NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_listening_events_user_revision ON listening_events(user_id, revision);
CREATE INDEX idx_listening_events_user_ended_at ON listening_events(user_id, ended_at);
CREATE INDEX idx_listening_events_user_book     ON listening_events(user_id, book_id);

-- user_stats: per-user materialized listening stats — one row per user.
-- id == user_id (1:1). Rolling windows recomputed lazily on catch-up.
CREATE TABLE user_stats (
    id                          TEXT    NOT NULL,
    user_id                     TEXT    NOT NULL,
    total_seconds_all_time      INTEGER NOT NULL DEFAULT 0,
    total_seconds_last_7_days   INTEGER NOT NULL DEFAULT 0,
    total_seconds_last_30_days  INTEGER NOT NULL DEFAULT 0,
    books_started               INTEGER NOT NULL DEFAULT 0,
    books_finished              INTEGER NOT NULL DEFAULT 0,
    current_streak_days         INTEGER NOT NULL DEFAULT 0,
    longest_streak_days         INTEGER NOT NULL DEFAULT 0,
    last_event_date             TEXT    NULL,
    revision                    INTEGER NOT NULL DEFAULT 0,
    created_at                  INTEGER NOT NULL,
    updated_at                  INTEGER NOT NULL,
    deleted_at                  INTEGER NULL,
    client_op_id                TEXT    NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_user_stats_user          ON user_stats(user_id);
CREATE        INDEX idx_user_stats_user_revision ON user_stats(user_id, revision);
