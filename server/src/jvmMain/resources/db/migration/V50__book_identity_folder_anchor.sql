-- Anchor book identity to (folder_id, root_rel_path) instead of (library_id, root_rel_path).
--
-- folder_id is a STABLE per-folder id. Anchoring identity to the folder (not the library) means a
-- folder remove+re-add that reuses the soft-deleted folder_id revives the same books under their
-- original UUIDs, instead of re-minting a fresh UUID per book and stranding every client's saved
-- references (playback position, shelves, collections → playback 404s library-wide).
--
-- The unique build is extremely unlikely to fail for single-library deployments: the pre-existing
-- UNIQUE(library_id, root_rel_path) plus the functional dependency folder_id → library_id forbid
-- duplicate (folder_id, root_rel_path) pairs. That dependency is NOT ironclad, though — sentinel
-- folder_ids ('unknown' from a scan miss, 'PENDING-LIB-C' from V20) can span multiple library_ids,
-- so a historical multi-library DB carrying such rows could in theory collide here. No data backfill
-- is needed — root_rel_path is already folder-relative and folder_id is populated on every live row.
DROP INDEX idx_book_natural_key;
CREATE UNIQUE INDEX idx_book_natural_key ON books(folder_id, root_rel_path);
DROP INDEX idx_book_inode;
CREATE INDEX idx_book_inode ON books(folder_id, inode);
