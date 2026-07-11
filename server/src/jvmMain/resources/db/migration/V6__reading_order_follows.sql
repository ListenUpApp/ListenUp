-- Reading-order follow-state (Integration Foundations §5.4): one row per
-- (user, series) recording which reading order is that user's active spoiler
-- clock for the series. `id` is the deterministic synthetic key
-- "<user_id>:<series_id>", computable on both sides — so client optimistic
-- writes, server rows, and digests agree on identity.

CREATE TABLE reading_order_follows (
    id                      TEXT    NOT NULL,
    user_id                 TEXT    NOT NULL,
    series_id               TEXT    NOT NULL,
    active_reading_order_id TEXT,
    created_at              INTEGER NOT NULL,
    updated_at              INTEGER NOT NULL,
    revision                INTEGER NOT NULL,
    deleted_at              INTEGER,
    client_op_id            TEXT,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_reading_order_follows_user_series ON reading_order_follows(user_id, series_id);
CREATE INDEX idx_reading_order_follows_revision ON reading_order_follows(revision);
