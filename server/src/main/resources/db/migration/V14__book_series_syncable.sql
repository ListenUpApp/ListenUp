-- Books-B1: promote `book_series` to a syncable domain.
ALTER TABLE book_series ADD COLUMN revision      INTEGER;
ALTER TABLE book_series ADD COLUMN created_at    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE book_series ADD COLUMN updated_at    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE book_series ADD COLUMN deleted_at    INTEGER;
ALTER TABLE book_series ADD COLUMN client_op_id  TEXT;

UPDATE book_series
SET revision   = (SELECT COUNT(*) FROM book_series s2 WHERE s2.rowid <= book_series.rowid),
    created_at = CAST(strftime('%s','now') AS INTEGER) * 1000,
    updated_at = CAST(strftime('%s','now') AS INTEGER) * 1000;

CREATE INDEX idx_book_series_revision ON book_series(revision);
