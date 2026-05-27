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
-- contributor_search was content-backed (content='contributors') in V19. We
-- switch it to contentless (content='') here because contributor_search now
-- carries an `aliases` column that does NOT exist in the `contributors` table:
-- SQLite's content-backed FTS5 reads the content table by column name on every
-- integrity check, and would throw SQLITE_CORRUPT_VTAB when it finds `aliases`
-- absent.  Contentless FTS5 avoids the content-table read entirely; the trigger
-- layer below keeps the index up to date.
--
-- DROP + CREATE is the only way to add a column to a virtual table in SQLite.
-- DESTRUCTIVE: existing FTS index dropped; core search (name/sort_name/description)
-- is backfilled inline below so search stays functional immediately after migration.
-- BookSearchReindexer will populate aliases on the next reindex pass.

DROP TABLE contributor_search;
CREATE VIRTUAL TABLE contributor_search USING fts5(
    name,
    sort_name,
    description,
    aliases,
    content='',
    contentless_delete=1,
    tokenize='unicode61 remove_diacritics 2'
);

-- Rebuild triggers to match the new column list and contentless mode.
DROP TRIGGER IF EXISTS contributors_ai;
DROP TRIGGER IF EXISTS contributors_au;
DROP TRIGGER IF EXISTS contributors_ad;

-- AI: new contributor has no aliases yet; seed with empty string.
CREATE TRIGGER contributors_ai AFTER INSERT ON contributors BEGIN
    INSERT INTO contributor_search(rowid, name, sort_name, description, aliases)
    VALUES (new.rowid, new.name, new.sort_name, new.description, '');
END;

-- AU: delete by rowid (contentless_delete=1 idiom), then insert the updated row.
-- Re-derive aliases from contributor_aliases so an update to the contributors row
-- never silently wipes FTS aliases that the application already indexed.
CREATE TRIGGER contributors_au AFTER UPDATE ON contributors BEGIN
    DELETE FROM contributor_search WHERE rowid = old.rowid;
    INSERT INTO contributor_search(rowid, name, sort_name, description, aliases)
    VALUES (new.rowid, new.name, new.sort_name, new.description,
        (SELECT COALESCE(GROUP_CONCAT(alias, ' '), '') FROM contributor_aliases WHERE contributor_id = new.id));
END;

-- AD: contributor deleted — cascade will remove contributor_aliases rows; remove FTS row.
CREATE TRIGGER contributors_ad AFTER DELETE ON contributors BEGIN
    DELETE FROM contributor_search WHERE rowid = old.rowid;
END;

-- Backfill name/sort_name/description from existing rows; aliases seeded empty.
-- BookSearchReindexer will populate aliases on the next reindex pass.
INSERT INTO contributor_search(rowid, name, sort_name, description, aliases)
    SELECT rowid, name, sort_name, description, '' FROM contributors;
