-- Hardcover pull (#601 B3). Server-only; nothing here is synced to a client as a row.
--
-- book_reads gains hc_read_id: the Hardcover read a pulled row mirrors (source = 'hardcover'), NULL for
--   ListenUp's own reads ('playback', 'reconcile'). Unique per user, so pulling twice can't duplicate;
--   SQLite treats NULLs as distinct, so ListenUp's own reads are unconstrained. A pulled row is deleted
--   when its read vanishes from Hardcover; ListenUp's own rows never are.
-- hardcover_connections gains the pull's state:
--   pull_cursor / pull_cursor_id: the (updated_at, id) of the last user_books row a committed page held,
--     verbatim from Hardcover. NULL = start from the beginning.
--   full_pull_started_at: set while a full pull (the daily one, or "Sync now") runs. Its completion
--     deletes pulled reads of shelf entries it never saw — that is how a deleted entry is noticed.
--   last_full_pull_at: when the last full pull completed.
--   pull_error: the error that outlasted the pull's retry cap, for the connection screen (PR 5).
-- hardcover_book_links gains pull_seen_at: when a pull last saw this book's shelf entry.

ALTER TABLE book_reads ADD COLUMN hc_read_id INTEGER;
CREATE UNIQUE INDEX idx_book_reads_user_hc_read ON book_reads(user_id, hc_read_id);

ALTER TABLE hardcover_connections ADD COLUMN pull_cursor TEXT;
ALTER TABLE hardcover_connections ADD COLUMN pull_cursor_id INTEGER;
ALTER TABLE hardcover_connections ADD COLUMN full_pull_started_at INTEGER;
ALTER TABLE hardcover_connections ADD COLUMN last_full_pull_at INTEGER;
ALTER TABLE hardcover_connections ADD COLUMN pull_error TEXT;

ALTER TABLE hardcover_book_links ADD COLUMN pull_seen_at INTEGER;
