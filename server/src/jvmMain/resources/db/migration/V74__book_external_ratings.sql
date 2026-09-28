-- How an outside catalog rates a book: one row per (book, source). Written only by the server's
-- ExternalRatingsFetcher; synced to everyone who can open the book. `enabled` mirrors the admin's
-- per-source switch so clients can hide a disabled source offline. `region` and `fetched_at` are
-- server-only and never cross the wire. Per-source health (last success, last error) lives in
-- server settings (RatingSourceSettings), not on this table — a row only exists once a source has
-- succeeded at least once, so table-derived health would be blind to a source that never has.
CREATE TABLE book_external_ratings (
    id           TEXT    NOT NULL,
    book_id      TEXT    NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    source       TEXT    NOT NULL,
    average      REAL    NOT NULL,
    count        INTEGER NOT NULL,
    enabled      INTEGER NOT NULL DEFAULT 1,
    region       TEXT,
    fetched_at   INTEGER NOT NULL,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    revision     INTEGER NOT NULL,
    deleted_at   INTEGER,
    client_op_id TEXT,
    PRIMARY KEY (book_id, source)
);

CREATE UNIQUE INDEX idx_book_external_ratings_id ON book_external_ratings(id);
CREATE INDEX idx_book_external_ratings_source ON book_external_ratings(source) WHERE deleted_at IS NULL;
CREATE INDEX idx_book_external_ratings_fetched ON book_external_ratings(fetched_at);
CREATE INDEX idx_book_external_ratings_revision ON book_external_ratings(revision);
