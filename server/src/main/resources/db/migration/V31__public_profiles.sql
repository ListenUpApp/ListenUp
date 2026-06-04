-- Public profiles: a global, book-agnostic projection of each user's socially-visible
-- facet (identity + listening aggregates). Synced to EVERY client (unlike the private
-- user_stats), it powers the Discover leaderboard and social roster. Maintained
-- server-side by PublicProfileMaintainer from the users + user_stats tables.
CREATE TABLE public_profiles (
    id                          TEXT    NOT NULL,
    display_name                TEXT    NOT NULL,
    avatar_type                 TEXT    NOT NULL DEFAULT 'auto',
    total_seconds_all_time      INTEGER NOT NULL DEFAULT 0,
    total_seconds_last_7_days   INTEGER NOT NULL DEFAULT 0,
    total_seconds_last_30_days  INTEGER NOT NULL DEFAULT 0,
    total_seconds_last_365_days INTEGER NOT NULL DEFAULT 0,
    books_finished              INTEGER NOT NULL DEFAULT 0,
    current_streak_days         INTEGER NOT NULL DEFAULT 0,
    longest_streak_days         INTEGER NOT NULL DEFAULT 0,
    created_at                  INTEGER NOT NULL,
    updated_at                  INTEGER NOT NULL,
    revision                    INTEGER NOT NULL,
    deleted_at                  INTEGER,
    client_op_id                TEXT,
    PRIMARY KEY (id)
);
CREATE INDEX idx_public_profiles_revision ON public_profiles(revision);
