-- is_inbox folded into the type column (type='INBOX' is the canonical discriminator);
-- drop the redundant column and replace the partial unique index with a type-based one.
DROP INDEX idx_collections_inbox;
ALTER TABLE collections DROP COLUMN is_inbox;
CREATE UNIQUE INDEX idx_collections_inbox ON collections(library_id) WHERE type = 'INBOX' AND deleted_at IS NULL;
