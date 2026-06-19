CREATE TABLE book_reads (
    id          TEXT    NOT NULL PRIMARY KEY,
    user_id     TEXT    NOT NULL,
    book_id     TEXT    NOT NULL,
    finished_at INTEGER NOT NULL,
    source      TEXT    NOT NULL,
    created_at  INTEGER NOT NULL
);
CREATE INDEX idx_book_reads_book ON book_reads (book_id);
CREATE INDEX idx_book_reads_user_book ON book_reads (user_id, book_id);
