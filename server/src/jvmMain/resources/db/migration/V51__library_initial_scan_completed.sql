ALTER TABLE libraries ADD COLUMN initial_scan_completed_at INTEGER;
-- Backfill: a library that already holds a live book has effectively finished its initial scan.
UPDATE libraries SET initial_scan_completed_at = updated_at
WHERE id IN (SELECT DISTINCT library_id FROM books WHERE deleted_at IS NULL);
