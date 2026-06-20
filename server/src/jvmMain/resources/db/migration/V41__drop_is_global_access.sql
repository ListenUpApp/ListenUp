-- Drop the vestigial is_global_access column.
-- The pure-union visibility rule replaced it: a book is visible iff it belongs to at
-- least one collection the viewer owns or holds an active grant on.  No code path reads
-- this column anymore; removing it eliminates a permanent source of confusion.
-- SQLite 3.35+ supports DROP COLUMN natively; there is no index on this column.
ALTER TABLE collections DROP COLUMN is_global_access;
