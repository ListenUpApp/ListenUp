-- Hardcover Want to Read → a ListenUp shelf (#1539). The bookkeeping here is server-only; the shelf
-- writes themselves go through the shelves / shelf_books sync domains like any other shelf change.
--
-- users gains:
--   starter_shelf_id: the "To Read" shelf made at registration, whatever it is later renamed to. Set by
--     ShelfRepository.createStarterShelf from now on; backfilled below as the user's earliest-created live
--     shelf named exactly 'To Read'. NULL = there is none, which counts as deleted.
--   hardcover_shelf_id: the public "Want to Read" shelf the pull made once the starter shelf was gone.
--   Neither carries an FK: shelves are soft-deleted, so whether one is alive is checked when it is read.
-- hardcover_shelf_entries: one row per (user, book) that Hardcover's Want to Read put on a shelf.
--   state ON_SHELF: Hardcover put it there, and takes it off when it leaves Want to Read.
--   state USER_REMOVED: the user took it off by hand; it is not put back while it stays on Want to Read.
--   hc_user_book_id: the Hardcover shelf entry it came from (how a status change finds it).
--   seen_at: when a pull last saw that entry ON Want to Read. A full pull's end takes off every record it
--     never saw — how an entry deleted on Hardcover is noticed.
--   The FKs cascade on a hard delete only; day to day books and shelves are soft-deleted.
-- Every connection's next pull is a full one, so Want to Read arrives now rather than within a day.

ALTER TABLE users ADD COLUMN starter_shelf_id TEXT;
ALTER TABLE users ADD COLUMN hardcover_shelf_id TEXT;

UPDATE users SET starter_shelf_id = (
    SELECT s.id FROM shelves AS s
    WHERE s.user_id = users.id AND s.name = 'To Read' AND s.deleted_at IS NULL
    ORDER BY s.created_at ASC, s.id ASC
    LIMIT 1
);

CREATE TABLE hardcover_shelf_entries (
    user_id         TEXT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    book_id         TEXT    NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    shelf_id        TEXT    NOT NULL REFERENCES shelves(id) ON DELETE CASCADE,
    hc_user_book_id INTEGER NOT NULL,
    state           TEXT    NOT NULL CHECK (state IN ('ON_SHELF', 'USER_REMOVED')),
    seen_at         INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    PRIMARY KEY (user_id, book_id)
);

CREATE INDEX idx_hardcover_shelf_entries_hc ON hardcover_shelf_entries(user_id, hc_user_book_id);
CREATE INDEX idx_hardcover_shelf_entries_book ON hardcover_shelf_entries(book_id);
CREATE INDEX idx_hardcover_shelf_entries_shelf ON hardcover_shelf_entries(shelf_id);

UPDATE hardcover_connections SET last_full_pull_at = NULL;
