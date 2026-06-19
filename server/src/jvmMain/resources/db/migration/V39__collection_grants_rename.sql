-- Phase 2a: collection_shares becomes principal-based collection_grants.
-- In-place rename preserves every row's revision (no new rows -> no sync_meta advance).
-- Behavior-preserving: existing user-shares become USER-principal grants.
ALTER TABLE collection_shares RENAME TO collection_grants;
ALTER TABLE collection_grants RENAME COLUMN shared_with_user_id TO principal_id;
ALTER TABLE collection_grants RENAME COLUMN shared_by_user_id TO granted_by_user_id;
ALTER TABLE collection_grants ADD COLUMN principal_type TEXT NOT NULL DEFAULT 'USER';
-- The old indexes carried over under their V24 names with their column refs rewritten
-- by RENAME COLUMN. Drop and recreate them principal-aware with collection_grants names.
DROP INDEX idx_collection_shares_active;
DROP INDEX idx_collection_shares_user;
DROP INDEX idx_collection_shares_revision;
CREATE UNIQUE INDEX idx_collection_grants_active ON collection_grants(collection_id, principal_type, principal_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_collection_grants_principal ON collection_grants(principal_type, principal_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_collection_grants_revision ON collection_grants(revision);
