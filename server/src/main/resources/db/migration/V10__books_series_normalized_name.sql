-- Adds normalized_name to book_series, mirroring ContributorTable's dedup shape.
-- Supersedes V6's name-only unique index because pre-normalizing into `name`
-- destroyed display casing on round-trip (e.g., "Stormlight Archive" → "stormlight archive").

ALTER TABLE book_series ADD COLUMN normalized_name VARCHAR(512) NOT NULL DEFAULT '';

UPDATE book_series SET normalized_name = LOWER(TRIM(name));

DROP INDEX idx_series_normalized;
CREATE UNIQUE INDEX idx_series_normalized ON book_series(normalized_name);
