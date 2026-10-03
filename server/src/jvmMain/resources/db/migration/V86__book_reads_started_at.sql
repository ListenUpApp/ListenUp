-- book_reads.started_at: the day the reader said they started a read, when they picked one in
-- "Mark as finished". NULL for every existing row and for every read whose start nobody picked —
-- history then derives the start from listening_events, as before. When set, it dates the read's
-- start on Hardcover when the read is sent as history (#1540).
ALTER TABLE book_reads ADD COLUMN started_at INTEGER;
