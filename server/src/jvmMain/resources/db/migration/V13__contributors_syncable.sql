-- Books-B1: promote `contributors` to a syncable domain.
ALTER TABLE contributors ADD COLUMN revision      INTEGER;
ALTER TABLE contributors ADD COLUMN created_at    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE contributors ADD COLUMN updated_at    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE contributors ADD COLUMN deleted_at    INTEGER;
ALTER TABLE contributors ADD COLUMN client_op_id  TEXT;

-- Backfill from the GLOBAL revision counter, not a local 1..N sequence.
-- Row rank r (1..N via the correlated COUNT) maps to counter+r, so every
-- backfilled revision is strictly greater than the pre-migration counter and
-- they are globally unique. The counter is advanced past them immediately
-- after, so the next `nextRevision()` write cannot collide with — or fall
-- below — a revision a client has already pulled past.
UPDATE contributors
SET revision   = (SELECT value FROM sync_meta WHERE key = 'revision_counter')
               + (SELECT COUNT(*) FROM contributors c2 WHERE c2.rowid <= contributors.rowid),
    created_at = CAST(strftime('%s','now') AS INTEGER) * 1000,
    updated_at = CAST(strftime('%s','now') AS INTEGER) * 1000;

-- Advance the global counter past every revision just assigned.
UPDATE sync_meta
SET value = value + (SELECT COUNT(*) FROM contributors)
WHERE key = 'revision_counter';

CREATE INDEX idx_contributors_revision ON contributors(revision);
