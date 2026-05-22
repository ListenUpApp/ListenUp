-- Books-B1: promote `book_series` to a syncable domain.
ALTER TABLE book_series ADD COLUMN revision      INTEGER;
ALTER TABLE book_series ADD COLUMN created_at    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE book_series ADD COLUMN updated_at    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE book_series ADD COLUMN deleted_at    INTEGER;
ALTER TABLE book_series ADD COLUMN client_op_id  TEXT;

-- Backfill from the GLOBAL revision counter, not a local 1..N sequence.
-- Row rank r (1..M via the correlated COUNT) maps to counter+r, so every
-- backfilled revision is strictly greater than the pre-migration counter and
-- they are globally unique. The counter is advanced past them immediately
-- after, so the next `nextRevision()` write cannot collide with — or fall
-- below — a revision a client has already pulled past. Flyway runs V13 before
-- V14, so the counter read here already reflects V13's contributor advance.
UPDATE book_series
SET revision   = (SELECT value FROM sync_meta WHERE key = 'revision_counter')
               + (SELECT COUNT(*) FROM book_series s2 WHERE s2.rowid <= book_series.rowid),
    created_at = CAST(strftime('%s','now') AS INTEGER) * 1000,
    updated_at = CAST(strftime('%s','now') AS INTEGER) * 1000;

-- Advance the global counter past every revision just assigned.
UPDATE sync_meta
SET value = value + (SELECT COUNT(*) FROM book_series)
WHERE key = 'revision_counter';

CREATE INDEX idx_book_series_revision ON book_series(revision);
