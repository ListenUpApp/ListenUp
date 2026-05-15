-- Server-internal mapping between books.id (UUID string) and book_search's
-- INTEGER rowid (an FTS5 constraint). BookRepository allocates rowids on first
-- FTS write and reuses them on subsequent updates.
CREATE TABLE book_search_map (
    book_id VARCHAR(36) NOT NULL PRIMARY KEY,
    rowid   INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_book_search_map_rowid ON book_search_map(rowid);

-- Recreate book_search with `contentless_delete=1` so BookRepository can use
-- plain `DELETE FROM book_search WHERE rowid = ?` for updates. The original V8
-- table was strictly contentless (`content=''`), which requires the special
-- INSERT-with-'delete' command and storing the original column values to
-- remove them from the inverted index. `contentless_delete=1` (SQLite 3.43+)
-- removes that constraint without forcing a content-backed table — we still
-- own population manually, but updates are now a clean DELETE + INSERT pair.
DROP TABLE book_search;
CREATE VIRTUAL TABLE book_search USING fts5(
    title,
    subtitle,
    description,
    contributor_names,
    series_names,
    content='',
    contentless_delete=1,
    tokenize='unicode61 remove_diacritics 2'
);
