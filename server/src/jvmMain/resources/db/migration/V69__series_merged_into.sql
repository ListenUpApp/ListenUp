-- Series merges were undone by rescans, exactly as contributor merges were before V61: the scanner
-- re-resolves series names from the audio files, and resolving a merged-away name revived the
-- tombstoned series and moved the book back into it. merged_into is the durable, server-only
-- redirect: set on the tombstoned series at merge time, followed by scan-time name resolution,
-- cleared by any upsert (which is also what revives the row). Unlike contributors, chains are NOT
-- flattened at write time — resolution walks them — so undoing a middle merge re-routes the
-- names that were merged into it.
ALTER TABLE book_series ADD COLUMN merged_into VARCHAR(36);
