-- Shelves: per-user, user-owned groupings of books. A shelf is private to its
-- owner or public for discovery; book membership is ordered (sort_order).
CREATE TABLE shelves (
    id           TEXT    NOT NULL,
    user_id      TEXT    NOT NULL,
    name         TEXT    NOT NULL,
    description  TEXT    NOT NULL DEFAULT '',
    is_private   INTEGER NOT NULL DEFAULT 0,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    revision     INTEGER NOT NULL,
    deleted_at   INTEGER,
    client_op_id TEXT,
    PRIMARY KEY (id)
);
CREATE INDEX idx_shelves_user ON shelves(user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_shelves_revision ON shelves(revision);

CREATE TABLE shelf_books (
    id           TEXT    NOT NULL,
    user_id      TEXT    NOT NULL,
    shelf_id     TEXT    NOT NULL,
    book_id      TEXT    NOT NULL,
    sort_order   INTEGER NOT NULL,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    revision     INTEGER NOT NULL,
    deleted_at   INTEGER,
    client_op_id TEXT,
    PRIMARY KEY (shelf_id, book_id),
    FOREIGN KEY (shelf_id) REFERENCES shelves(id) ON DELETE CASCADE,
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_shelf_books_id ON shelf_books(id);
CREATE INDEX idx_shelf_books_shelf ON shelf_books(shelf_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_shelf_books_user ON shelf_books(user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_shelf_books_revision ON shelf_books(revision);
