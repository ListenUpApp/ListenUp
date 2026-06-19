-- Per-user playback preferences (was on the Go server, never ported to Kotlin — issue #599).
-- One row per user, created on first write. Cascade-deletes with the user. updated_at is an
-- ISO-8601 string set on every write; the RPC DTO does not surface it (kept for parity/debugging).
CREATE TABLE user_settings (
    user_id                     TEXT NOT NULL PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    default_playback_speed      REAL NOT NULL DEFAULT 1.0,
    default_skip_forward_sec    INTEGER NOT NULL DEFAULT 30,
    default_skip_backward_sec   INTEGER NOT NULL DEFAULT 10,
    default_sleep_timer_min     INTEGER,
    shake_to_reset_sleep_timer  INTEGER NOT NULL DEFAULT 0,
    updated_at                  TEXT NOT NULL
);
