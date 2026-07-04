-- Phase 2 (sync-core): promote `activities` to a syncable, book-gated MirroredDomain.
-- Adds the revision-cursor substrate so the social activity feed rides the data channel
-- (live + delta-recoverable) instead of the lossy ActivityChanged control nudge.
ALTER TABLE activities ADD COLUMN revision      INTEGER;
ALTER TABLE activities ADD COLUMN updated_at    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE activities ADD COLUMN deleted_at    INTEGER;
ALTER TABLE activities ADD COLUMN client_op_id  TEXT;

-- Backfill from the GLOBAL revision counter, not a local 1..N sequence. Row rank r (1..N via
-- the correlated COUNT) maps to counter+r, so every backfilled revision is strictly greater than
-- the pre-migration counter and globally unique. The counter is advanced past them immediately
-- after, so the next `nextRevision()` write cannot collide with — or fall below — a revision a
-- client has already pulled past. (`created_at` already exists on activities; leave it untouched.)
UPDATE activities
SET revision   = (SELECT value FROM sync_meta WHERE key = 'revision_counter')
               + (SELECT COUNT(*) FROM activities a2 WHERE a2.rowid <= activities.rowid),
    updated_at = CAST(strftime('%s','now') AS INTEGER) * 1000;

-- Advance the global counter past every revision just assigned.
UPDATE sync_meta
SET value = value + (SELECT COUNT(*) FROM activities)
WHERE key = 'revision_counter';

CREATE INDEX idx_activities_revision ON activities(revision);
