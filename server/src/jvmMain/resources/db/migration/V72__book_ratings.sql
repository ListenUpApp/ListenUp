-- One listener's rating of one book: 2..10 half-star units plus an optional note.
-- Syncable (opaque id, revision, soft delete) and book-scoped: readable by everyone who can open
-- the book, written only through BookRatingService by the listener it belongs to.
CREATE TABLE book_ratings (
    id           TEXT    NOT NULL,
    book_id      TEXT    NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    user_id      TEXT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    half_stars   INTEGER NOT NULL CHECK (half_stars BETWEEN 2 AND 10),
    note         TEXT,
    rated_at     INTEGER NOT NULL,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    revision     INTEGER NOT NULL,
    deleted_at   INTEGER,
    client_op_id TEXT,
    PRIMARY KEY (book_id, user_id)
);

CREATE UNIQUE INDEX idx_book_ratings_id ON book_ratings(id);
CREATE INDEX idx_book_ratings_user ON book_ratings(user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_book_ratings_revision ON book_ratings(revision);
