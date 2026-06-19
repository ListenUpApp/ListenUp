-- Append-only social-activity log: one row per recordable user action (started/finished a
-- book, hit a listening milestone, created a shelf, …). Read back as a paginated,
-- most-recent-first feed via the `feed` RPC. NOT a syncable client domain — no sync columns,
-- clients never write it. Type-specific columns are nullable / defaulted per activity type.
CREATE TABLE activities (
    id             TEXT    NOT NULL,
    user_id        TEXT    NOT NULL,
    type           TEXT    NOT NULL,
    created_at     INTEGER NOT NULL,
    book_id        TEXT,
    is_reread      INTEGER NOT NULL DEFAULT 0,
    duration_ms    INTEGER NOT NULL DEFAULT 0,
    milestone_value INTEGER NOT NULL DEFAULT 0,
    milestone_unit TEXT,
    shelf_id       TEXT,
    shelf_name     TEXT,
    PRIMARY KEY (id)
);
CREATE INDEX idx_activities_created_at ON activities(created_at);
