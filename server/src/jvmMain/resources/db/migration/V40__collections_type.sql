-- Phase 2b-i: add a first-class collection type. NORMAL by default; existing inboxes become INBOX.
-- is_inbox/is_global_access are retained for now (the current visibility rule + admin UI still read
-- them); they are removed in a later phase once the pure-union rule is live.
ALTER TABLE collections ADD COLUMN type TEXT NOT NULL DEFAULT 'NORMAL';
UPDATE collections SET type = 'INBOX' WHERE is_inbox = 1;
CREATE INDEX idx_collections_type ON collections(type);
