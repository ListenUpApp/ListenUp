-- Phase 2b-i: add a first-class collection type. NORMAL by default; existing inboxes become INBOX.
-- is_inbox/is_global_access are retained for now (the current visibility rule + admin UI still read
-- them); they are removed in a later phase once the pure-union rule is live.
ALTER TABLE collections ADD COLUMN type TEXT NOT NULL DEFAULT 'NORMAL';
UPDATE collections SET type = 'INBOX' WHERE is_inbox = 1;
CREATE INDEX idx_collections_type ON collections(type);
-- ALL_BOOKS is a hard singleton per library (mirroring the inbox's idx_collections_inbox). The
-- partial unique index makes a duplicate structurally impossible, backstopping the lazy
-- find-or-create against a concurrent first-call race. Zero rows match at V40 time (ALL_BOOKS rows
-- are created later by V41 / getOrCreateSystemCollection), so creating it now is safe.
CREATE UNIQUE INDEX idx_collections_all_books ON collections(library_id) WHERE type = 'ALL_BOOKS' AND deleted_at IS NULL;
