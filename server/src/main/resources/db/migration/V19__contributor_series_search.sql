-- B2a search: FTS5 indexes for contributors and series, backed by
-- sync triggers so the indexes track their source tables without
-- application-layer invalidation. Backfilled inline from current data.
--
-- Both tables use the same tokenizer as book_search (V8/V9):
--   unicode61 remove_diacritics 2
-- Consistency across all FTS indexes makes query-time behaviour
-- predictable — no user surprise when "Müller" matches differently
-- across domains.
--
-- Unlike book_search, contributors and series use content-backed FTS5
-- (content='<table>') rather than the contentless form. This lets SQLite
-- verify content on read and avoids the separate *_search_map rowid
-- indirection that books required. The triggers below keep the shadow
-- index in sync.

-- ── contributor_search ────────────────────────────────────────────────

CREATE VIRTUAL TABLE contributor_search USING fts5(
    name,
    sort_name,
    description,
    content='contributors',
    content_rowid='rowid',
    tokenize='unicode61 remove_diacritics 2'
);

CREATE TRIGGER contributors_ai AFTER INSERT ON contributors BEGIN
    INSERT INTO contributor_search(rowid, name, sort_name, description)
    VALUES (new.rowid, new.name, new.sort_name, new.description);
END;

CREATE TRIGGER contributors_au AFTER UPDATE ON contributors BEGIN
    INSERT INTO contributor_search(contributor_search, rowid, name, sort_name, description)
    VALUES ('delete', old.rowid, old.name, old.sort_name, old.description);
    INSERT INTO contributor_search(rowid, name, sort_name, description)
    VALUES (new.rowid, new.name, new.sort_name, new.description);
END;

CREATE TRIGGER contributors_ad AFTER DELETE ON contributors BEGIN
    INSERT INTO contributor_search(contributor_search, rowid, name, sort_name, description)
    VALUES ('delete', old.rowid, old.name, old.sort_name, old.description);
END;

-- Backfill from existing rows.
INSERT INTO contributor_search(rowid, name, sort_name, description)
    SELECT rowid, name, sort_name, description FROM contributors;

-- ── series_search ──────────────────────────────────────────────────────

CREATE VIRTUAL TABLE series_search USING fts5(
    name,
    sort_name,
    description,
    content='book_series',
    content_rowid='rowid',
    tokenize='unicode61 remove_diacritics 2'
);

CREATE TRIGGER series_ai AFTER INSERT ON book_series BEGIN
    INSERT INTO series_search(rowid, name, sort_name, description)
    VALUES (new.rowid, new.name, new.sort_name, new.description);
END;

CREATE TRIGGER series_au AFTER UPDATE ON book_series BEGIN
    INSERT INTO series_search(series_search, rowid, name, sort_name, description)
    VALUES ('delete', old.rowid, old.name, old.sort_name, old.description);
    INSERT INTO series_search(rowid, name, sort_name, description)
    VALUES (new.rowid, new.name, new.sort_name, new.description);
END;

CREATE TRIGGER series_ad AFTER DELETE ON book_series BEGIN
    INSERT INTO series_search(series_search, rowid, name, sort_name, description)
    VALUES ('delete', old.rowid, old.name, old.sort_name, old.description);
END;

-- Backfill from existing rows.
INSERT INTO series_search(rowid, name, sort_name, description)
    SELECT rowid, name, sort_name, description FROM book_series;
