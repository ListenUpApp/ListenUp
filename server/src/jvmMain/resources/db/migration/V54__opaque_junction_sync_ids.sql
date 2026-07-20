-- V54__opaque_junction_sync_ids.sql — SERVER-SYNC-04: junction wire ids become opaque.
-- The id column of every junction table previously encoded the natural pair
-- ("$collectionId:$bookId"), so ungated tombstones leaked the association to every
-- authenticated user. Rewrite ids to random values; digests change, clients full-resync.
-- All four tables already have an `id TEXT NOT NULL` column with a UNIQUE INDEX (verified
-- against CollectionBooks.sq / BookTags.sq / BookMoods.sq / ShelfBooks.sq) — this migration
-- is data-only, no ADD COLUMN, no golden-schema regeneration required.
UPDATE collection_books SET id = lower(hex(randomblob(16)));
UPDATE book_tags SET id = lower(hex(randomblob(16)));
UPDATE book_moods SET id = lower(hex(randomblob(16)));
UPDATE shelf_books SET id = lower(hex(randomblob(16)));
