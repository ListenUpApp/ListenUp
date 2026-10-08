-- Reading orders (#962): a named, ordered list of books belonging to exactly one series (any level of
-- the hierarchy). Library-wide: every authenticated user receives every row, like book_series. The
-- built-in Series order and Publication order are computed by clients and never stored.
CREATE TABLE reading_orders (
    id              TEXT    NOT NULL PRIMARY KEY,   -- client-minted UUID (offline create)
    series_id       TEXT    NOT NULL,               -- no FK, like book_series.parent_id
    name            TEXT    NOT NULL,
    normalized_name TEXT    NOT NULL,               -- ReadingOrderName.normalize(name): uniqueness per series
    created_by      TEXT    NOT NULL,               -- the maker's user id (not user_id: orders are not user-scoped)
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    revision        INTEGER NOT NULL,
    deleted_at      INTEGER,
    client_op_id    TEXT
);
CREATE UNIQUE INDEX idx_reading_orders_series_name
    ON reading_orders(series_id, normalized_name) WHERE deleted_at IS NULL;
CREATE INDEX idx_reading_orders_series ON reading_orders(series_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_reading_orders_revision ON reading_orders(revision);

-- One row per (order, book), keyed by the natural pair like book_tags, so removing and re-adding a book
-- revives the same row. `id` is an opaque client-minted wire id (SERVER-SYNC-04), never the pair.
-- Per-row access-gated on book_id. Positions are sparse; clients number by rank.
CREATE TABLE reading_order_books (
    id               TEXT    NOT NULL,
    reading_order_id TEXT    NOT NULL REFERENCES reading_orders(id) ON DELETE CASCADE,
    book_id          TEXT    NOT NULL,
    position         INTEGER NOT NULL,
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL,
    revision         INTEGER NOT NULL,
    deleted_at       INTEGER,
    client_op_id     TEXT,
    PRIMARY KEY (reading_order_id, book_id)
);
CREATE UNIQUE INDEX idx_reading_order_books_id ON reading_order_books(id);
CREATE INDEX idx_reading_order_books_book ON reading_order_books(book_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_reading_order_books_revision ON reading_order_books(revision);

-- A user's choice of order for one series. User-scoped. `id` is the deterministic "<user_id>:<series_id>",
-- computable on both sides. A tombstoned row means "no choice here — inherit from the nearest ancestor".
CREATE TABLE reading_order_follows (
    id               TEXT    NOT NULL PRIMARY KEY,
    user_id          TEXT    NOT NULL,
    series_id        TEXT    NOT NULL,
    choice           TEXT    NOT NULL,   -- 'SERIES' | 'PUBLICATION' | 'ORDER'
    reading_order_id TEXT,               -- set iff choice = 'ORDER'
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL,
    revision         INTEGER NOT NULL,
    deleted_at       INTEGER,
    client_op_id     TEXT
);
CREATE UNIQUE INDEX idx_reading_order_follows_user_series ON reading_order_follows(user_id, series_id);
CREATE INDEX idx_reading_order_follows_order ON reading_order_follows(reading_order_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_reading_order_follows_revision ON reading_order_follows(revision);

-- What a series merge did to reading orders, so undo can hand each one back: the merge moves the source's
-- live orders onto the target (renaming one whose name the target already uses), and this keeps each
-- order's name from before the move. The source is series_merge_receipts.source_id.
CREATE TABLE series_merge_receipt_reading_orders (
    receipt_id       TEXT NOT NULL REFERENCES series_merge_receipts(id) ON DELETE CASCADE,
    reading_order_id TEXT NOT NULL,
    name             TEXT NOT NULL,
    PRIMARY KEY (receipt_id, reading_order_id)
);

-- The reading-order permission (#962). DEFAULT 1: additive, undoable work defaults on, so every existing
-- member holds it; an admin can turn it off. ROOT/ADMIN hold it implicitly in PermissionPolicy. The roster
-- projection mirrors it, like can_curate_library.
ALTER TABLE users ADD COLUMN can_make_reading_orders INTEGER NOT NULL DEFAULT 1;
ALTER TABLE admin_user_roster ADD COLUMN can_make_reading_orders INTEGER NOT NULL DEFAULT 1;
