-- Sync Foundation: global revision counter + Tags validation domain.
-- The counter is incremented atomically inside every syncable write transaction.

CREATE TABLE sync_meta (
    key TEXT NOT NULL PRIMARY KEY,
    value INTEGER NOT NULL
);

INSERT INTO sync_meta (key, value) VALUES ('revision_counter', 0);

CREATE TABLE tags (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    revision INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER,
    client_op_id TEXT
);

CREATE INDEX idx_tags_revision ON tags (revision);
CREATE INDEX idx_tags_name ON tags (name);
