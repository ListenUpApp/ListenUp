-- V53__drop_blurhash_columns.sql — remove the dormant BlurHash feature.
-- The server never generated BlurHashes (no Kotlin/Native image decoder), so
-- contributors.image_blur_hash and book_series.cover_blur_hash were always NULL.
-- DESTRUCTIVE: removing dead BlurHash feature; the dropped columns were always NULL (no data loss).
ALTER TABLE contributors DROP COLUMN image_blur_hash;
ALTER TABLE book_series DROP COLUMN cover_blur_hash;
