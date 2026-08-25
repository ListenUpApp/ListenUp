-- The `inbox_enabled` name was dishonest: it never controlled whether the inbox exists.
--
-- The inbox is unconditional — a book the scanner cannot understand belongs there whatever an
-- admin has configured, because the alternative is the log line nobody reads. What this column
-- actually gates is narrower: whether *healthy* new books are ALSO held for review before members
-- can see them. Renaming it is the change that makes the always-on inbox coherent rather than a
-- UI story told over a contradicting schema.
--
-- Values carry over unchanged: 1 still means "hold new books", 0 still means "release them".
ALTER TABLE libraries RENAME COLUMN inbox_enabled TO hold_new_books_for_review;
