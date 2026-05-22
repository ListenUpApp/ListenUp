-- Books-B1: promote `contributors` to a syncable domain.
ALTER TABLE contributors ADD COLUMN revision      INTEGER;
ALTER TABLE contributors ADD COLUMN created_at    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE contributors ADD COLUMN updated_at    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE contributors ADD COLUMN deleted_at    INTEGER;
ALTER TABLE contributors ADD COLUMN client_op_id  TEXT;

UPDATE contributors
SET revision   = (SELECT COUNT(*) FROM contributors c2 WHERE c2.rowid <= contributors.rowid),
    created_at = CAST(strftime('%s','now') AS INTEGER) * 1000,
    updated_at = CAST(strftime('%s','now') AS INTEGER) * 1000;

CREATE INDEX idx_contributors_revision ON contributors(revision);
