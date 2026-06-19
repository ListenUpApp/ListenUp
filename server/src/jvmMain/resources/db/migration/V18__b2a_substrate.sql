-- Books-B2a substrate: enrichment columns for contributors and book_series,
-- plus the server-only metadata_cache table backing the Audible/iTunes lookup
-- cache. All new columns default null; no existing rows need backfill ("no
-- existing users" policy applies to server-side data).

ALTER TABLE contributors ADD COLUMN asin         TEXT NULL;
ALTER TABLE contributors ADD COLUMN description  TEXT NULL;
ALTER TABLE contributors ADD COLUMN image_path   TEXT NULL;
ALTER TABLE contributors ADD COLUMN image_blur_hash TEXT NULL;
ALTER TABLE contributors ADD COLUMN birth_date   TEXT NULL;
ALTER TABLE contributors ADD COLUMN death_date   TEXT NULL;
ALTER TABLE contributors ADD COLUMN website      TEXT NULL;

ALTER TABLE book_series ADD COLUMN asin          TEXT NULL;
ALTER TABLE book_series ADD COLUMN description   TEXT NULL;
ALTER TABLE book_series ADD COLUMN cover_path    TEXT NULL;
ALTER TABLE book_series ADD COLUMN cover_blur_hash TEXT NULL;

CREATE TABLE metadata_cache (
    cache_key    TEXT    NOT NULL PRIMARY KEY,
    region       TEXT    NOT NULL,
    payload_json TEXT    NOT NULL,
    fetched_at   INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL
);
CREATE INDEX idx_metadata_cache_expires ON metadata_cache(expires_at);
