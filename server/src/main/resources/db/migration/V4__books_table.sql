CREATE TABLE books (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    library_id     VARCHAR(36) NOT NULL REFERENCES libraries(id),
    title          VARCHAR(1024) NOT NULL,
    sort_title     VARCHAR(1024),
    subtitle       VARCHAR(1024),
    description    TEXT,
    publish_year   INTEGER,
    publisher      VARCHAR(512),
    language       VARCHAR(16),
    isbn           VARCHAR(32),
    asin           VARCHAR(32),
    abridged       BOOLEAN NOT NULL DEFAULT 0,
    explicit       BOOLEAN NOT NULL DEFAULT 0,
    total_duration BIGINT NOT NULL,
    cover_source   VARCHAR(32),
    cover_path     VARCHAR(1024),
    cover_hash     VARCHAR(64),
    root_rel_path  VARCHAR(1024) NOT NULL,
    inode          BIGINT,
    scanned_at     BIGINT NOT NULL,
    -- SyncableTable inherited:
    revision       BIGINT NOT NULL,
    created_at     BIGINT NOT NULL,
    updated_at     BIGINT NOT NULL,
    deleted_at     BIGINT,
    client_op_id   VARCHAR(64)
);
CREATE UNIQUE INDEX idx_book_natural_key ON books(library_id, root_rel_path);
CREATE INDEX idx_book_inode ON books(library_id, inode);
CREATE INDEX idx_book_sort_title ON books(library_id, sort_title);
CREATE INDEX idx_book_revision ON books(revision);
CREATE INDEX idx_book_updated_at ON books(updated_at);
