-- Keep one book off Hardcover (#1541). Server-only; a client sees it as HardcoverBookMatch.KeptOff and
-- HardcoverConnection.Connected.keptOffBookCount.
--
-- hardcover_book_exclusions: one row per (user, book) the listener keeps off Hardcover. Nothing about the
--   book is sent (no start, progress, finish or history) and nothing comes in (no pulled reads, no Want to
--   Read shelving); it is not in Needs a match.
--   excluded_at: when it was kept off. Syncing it again sends, as history, the listener's own reads of it
--     finished since then.
--   It belongs to the listener, not the connection: a disconnect keeps it, and so does connecting another
--   Hardcover account.
--   The FKs cascade on a hard delete only; day to day books are soft-deleted, and every query joins to
--   live books.
--
-- FKs live here only: SQLDelight can't resolve cross-.sq references, so the .sq file omits them.

CREATE TABLE hardcover_book_exclusions (
    user_id     TEXT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    book_id     TEXT    NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    excluded_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, book_id)
);

CREATE INDEX idx_hardcover_book_exclusions_book ON hardcover_book_exclusions(book_id);
