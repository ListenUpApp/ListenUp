-- #548: activities must order/display by the real event time, not insert time.
-- Add occurred_at, backfill existing rows to created_at, and index it for the feed keyset.
ALTER TABLE activities ADD COLUMN occurred_at INTEGER NOT NULL DEFAULT 0;
UPDATE activities SET occurred_at = created_at;
CREATE INDEX idx_activities_occurred_at ON activities(occurred_at);
