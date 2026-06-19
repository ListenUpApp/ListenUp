-- Collections: user-owned (or system-inbox) groupings of books. Privacy boundary
-- per the access model — uncollected books are public; collected books are gated.
CREATE TABLE collections (
    id                TEXT    NOT NULL,
    library_id        TEXT    NOT NULL,
    owner_id          TEXT    NOT NULL,
    name              TEXT    NOT NULL,
    is_inbox          INTEGER NOT NULL DEFAULT 0,
    is_global_access  INTEGER NOT NULL DEFAULT 0,
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,
    revision          INTEGER NOT NULL,
    deleted_at        INTEGER,
    client_op_id      TEXT,
    PRIMARY KEY (id),
    FOREIGN KEY (library_id) REFERENCES libraries(id) ON DELETE CASCADE
);
CREATE INDEX idx_collections_owner ON collections(owner_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_collections_library ON collections(library_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_collections_revision ON collections(revision);
CREATE UNIQUE INDEX idx_collections_inbox ON collections(library_id) WHERE is_inbox = 1 AND deleted_at IS NULL;

CREATE TABLE collection_books (
    id            TEXT    NOT NULL,
    collection_id TEXT    NOT NULL,
    book_id       TEXT    NOT NULL,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL,
    revision      INTEGER NOT NULL,
    deleted_at    INTEGER,
    client_op_id  TEXT,
    PRIMARY KEY (collection_id, book_id),
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE,
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_collection_books_id ON collection_books(id);
CREATE INDEX idx_collection_books_book ON collection_books(book_id, collection_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_collection_books_revision ON collection_books(revision);

CREATE TABLE collection_shares (
    id                  TEXT    NOT NULL,
    collection_id       TEXT    NOT NULL,
    shared_with_user_id TEXT    NOT NULL,
    shared_by_user_id   TEXT    NOT NULL,
    permission          TEXT    NOT NULL DEFAULT 'read',
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER NOT NULL,
    revision            INTEGER NOT NULL,
    deleted_at          INTEGER,
    client_op_id        TEXT,
    PRIMARY KEY (id),
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE,
    FOREIGN KEY (shared_with_user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_collection_shares_active ON collection_shares(collection_id, shared_with_user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_collection_shares_user ON collection_shares(shared_with_user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_collection_shares_revision ON collection_shares(revision);
