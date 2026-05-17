CREATE TABLE book_chapters (
    book_id    VARCHAR(36) NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    ordinal    INTEGER NOT NULL,
    id         VARCHAR(36) NOT NULL,
    title      VARCHAR(1024) NOT NULL,
    duration   BIGINT NOT NULL,
    start_time BIGINT NOT NULL,
    PRIMARY KEY (book_id, ordinal)
);

CREATE TABLE book_audio_files (
    book_id  VARCHAR(36) NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    ordinal  INTEGER NOT NULL,
    id       VARCHAR(36) NOT NULL,
    filename VARCHAR(1024) NOT NULL,
    format   VARCHAR(32) NOT NULL,
    codec    VARCHAR(32) NOT NULL,
    duration BIGINT NOT NULL,
    size     BIGINT NOT NULL,
    PRIMARY KEY (book_id, ordinal)
);
