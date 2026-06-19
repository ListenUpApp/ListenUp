CREATE TABLE book_series (
    id        VARCHAR(36) NOT NULL PRIMARY KEY,
    name      VARCHAR(512) NOT NULL,
    sort_name VARCHAR(512)
);
CREATE UNIQUE INDEX idx_series_normalized ON book_series(name COLLATE NOCASE);

CREATE TABLE book_series_memberships (
    book_id   VARCHAR(36) NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    series_id VARCHAR(36) NOT NULL REFERENCES book_series(id),
    sequence  VARCHAR(64),
    ordinal   INTEGER NOT NULL,
    PRIMARY KEY (book_id, series_id)
);
CREATE INDEX idx_bsm_series ON book_series_memberships(series_id);
