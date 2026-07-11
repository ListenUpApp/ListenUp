-- Reading orders: user-owned, named, ordered, attributed lists of books.
-- Near-exact sibling of the shelves substrate (V1 baseline), with one net-new
-- column: attribution (free text — who recommends this order / why).

CREATE TABLE reading_orders (
    id           TEXT    NOT NULL,
    user_id      TEXT    NOT NULL,
    name         TEXT    NOT NULL,
    description  TEXT    NOT NULL DEFAULT '',
    attribution  TEXT    NOT NULL DEFAULT '',
    is_private   INTEGER NOT NULL DEFAULT 0,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    revision     INTEGER NOT NULL,
    deleted_at   INTEGER,
    client_op_id TEXT,
    PRIMARY KEY (id)
);

CREATE INDEX idx_reading_orders_user ON reading_orders(user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_reading_orders_revision ON reading_orders(revision);

CREATE TABLE reading_order_books (
    id               TEXT    NOT NULL,
    user_id          TEXT    NOT NULL,
    reading_order_id TEXT    NOT NULL,
    book_id          TEXT    NOT NULL,
    sort_order       INTEGER NOT NULL,
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL,
    revision         INTEGER NOT NULL,
    deleted_at       INTEGER,
    client_op_id     TEXT,
    PRIMARY KEY (reading_order_id, book_id),
    FOREIGN KEY (reading_order_id) REFERENCES reading_orders(id) ON DELETE CASCADE,
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_reading_order_books_id ON reading_order_books(id);
CREATE INDEX idx_reading_order_books_reading_order ON reading_order_books(reading_order_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_reading_order_books_book ON reading_order_books(book_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_reading_order_books_user ON reading_order_books(user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_reading_order_books_revision ON reading_order_books(revision);
