CREATE TABLE book_documents (
    book_id  VARCHAR(36)   NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    ordinal  INTEGER       NOT NULL,
    id       VARCHAR(36)   NOT NULL,
    filename VARCHAR(1024) NOT NULL,
    format   VARCHAR(32)   NOT NULL,
    size     BIGINT        NOT NULL,
    hash     VARCHAR(64)   NOT NULL,
    PRIMARY KEY (book_id, ordinal)
);
