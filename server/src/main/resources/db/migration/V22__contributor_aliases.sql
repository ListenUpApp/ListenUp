-- V22: contributor_aliases table + contributor_search FTS5 extension for Books-C2.
-- Aliases are stored one-row-per-alias in contributor_aliases and denormalized as a
-- space-separated string in contributor_search.aliases for FTS matching.
-- Population of the aliases column is the application's responsibility
-- (BookSearchReindexer.reindexContributorAliases) — not managed by triggers.
--
-- The AI trigger seeds aliases as '' (empty); the AU trigger re-derives the aliases
-- string from contributor_aliases so a contributor update never silently wipes FTS
-- aliases that were already indexed. The AD trigger deletes the FTS row as before.

-- ── contributor_aliases ───────────────────────────────────────────────────────

CREATE TABLE contributor_aliases (
    contributor_id TEXT NOT NULL,
    alias          TEXT NOT NULL,
    PRIMARY KEY (contributor_id, alias),
    FOREIGN KEY (contributor_id) REFERENCES contributors(id) ON DELETE CASCADE
);

CREATE INDEX idx_contributor_aliases_contributor_id ON contributor_aliases(contributor_id);

-- ── contributor_search: extend with aliases column ────────────────────────────
-- contributor_search is content-backed (content='contributors'). DROP + CREATE is
-- required to add a column; there is no ALTER VIRTUAL TABLE in SQLite.
-- The existing columns (name, sort_name, description) are preserved verbatim.
-- DESTRUCTIVE: existing FTS index dropped; application will repopulate aliases via
-- BookSearchReindexer on the next reindex pass. Core search (name/sort_name/description)
-- is backfilled inline below so search stays functional immediately after migration.

DROP TABLE contributor_search;
CREATE VIRTUAL TABLE contributor_search USING fts5(
    name,
    sort_name,
    description,
    aliases,
    content='contributors',
    content_rowid='rowid',
    tokenize='unicode61 remove_diacritics 2'
);

-- Rebuild triggers to match the new column list.
DROP TRIGGER IF EXISTS contributors_ai;
DROP TRIGGER IF EXISTS contributors_au;
DROP TRIGGER IF EXISTS contributors_ad;

-- AI: new contributor has no aliases yet; seed with empty string.
CREATE TRIGGER contributors_ai AFTER INSERT ON contributors BEGIN
    INSERT INTO contributor_search(rowid, name, sort_name, description, aliases)
    VALUES (new.rowid, new.name, new.sort_name, new.description, '');
END;

-- AU: re-derive aliases from contributor_aliases so an update to the contributors row
-- never wipes FTS aliases that the application already indexed.
CREATE TRIGGER contributors_au AFTER UPDATE ON contributors BEGIN
    INSERT INTO contributor_search(contributor_search, rowid, name, sort_name, description, aliases)
    VALUES ('delete', old.rowid, old.name, old.sort_name, old.description,
        (SELECT COALESCE(GROUP_CONCAT(alias, ' '), '') FROM contributor_aliases WHERE contributor_id = old.id));
    INSERT INTO contributor_search(rowid, name, sort_name, description, aliases)
    VALUES (new.rowid, new.name, new.sort_name, new.description,
        (SELECT COALESCE(GROUP_CONCAT(alias, ' '), '') FROM contributor_aliases WHERE contributor_id = new.id));
END;

-- AD: contributor deleted — cascade will remove contributor_aliases rows; remove FTS row.
CREATE TRIGGER contributors_ad AFTER DELETE ON contributors BEGIN
    INSERT INTO contributor_search(contributor_search, rowid, name, sort_name, description, aliases)
    VALUES ('delete', old.rowid, old.name, old.sort_name, old.description, '');
END;

-- Backfill name/sort_name/description from existing rows; aliases seeded empty.
-- BookSearchReindexer will populate aliases on the next reindex pass.
INSERT INTO contributor_search(rowid, name, sort_name, description, aliases)
    SELECT rowid, name, sort_name, description, '' FROM contributors;
