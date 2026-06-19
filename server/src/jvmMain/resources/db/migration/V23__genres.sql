-- Genres substrate: genres (hierarchical, syncable) + book_genres junction +
-- genre_aliases (raw-string → genre_id resolution) + pending_book_genres (raw
-- strings awaiting curator mapping) + book_search.genres column. Closes the
-- scanner-genre-string persistence gap.
--
-- Per Books-C2 audit findings (docs/superpowers/findings/2026-05-27-books-c2-audit.md):
-- contentless FTS5 tables MUST declare `contentless_delete=1` AND any custom delete
-- statements use the canonical `DELETE FROM ft WHERE rowid = ?` form (not the legacy
-- `INSERT INTO ft(ft, rowid) VALUES ('delete', rowid)` shape).

-- ── genres ────────────────────────────────────────────────────────────────────
-- SyncableTable substrate columns mirror tags/contributors/series. Hierarchy via
-- materialized path: `path` stores the slash-separated slug path (e.g.
-- "/fiction/fantasy/epic-fantasy"); `depth` is the cached path component count;
-- `parent_id` is the direct parent for join-based child queries.
CREATE TABLE genres (
    id           TEXT    PRIMARY KEY,
    name         TEXT    NOT NULL,
    slug         TEXT    NOT NULL,
    path         TEXT    NOT NULL,
    parent_id    TEXT,
    depth        INTEGER NOT NULL DEFAULT 0,
    sort_order   INTEGER NOT NULL DEFAULT 0,
    color        TEXT,
    description  TEXT,
    -- SyncableTable substrate columns
    revision     INTEGER NOT NULL DEFAULT 0,
    updated_at   INTEGER NOT NULL DEFAULT 0,
    created_at   INTEGER NOT NULL DEFAULT 0,
    deleted_at   INTEGER,
    client_op_id TEXT,
    FOREIGN KEY (parent_id) REFERENCES genres(id) ON DELETE SET NULL
);

-- Unique slug among live (non-tombstoned) genres.
CREATE UNIQUE INDEX idx_genres_slug_live ON genres(slug) WHERE deleted_at IS NULL;

-- Path index for descendant queries (`path = ? OR path LIKE ? || '/%'`).
CREATE INDEX idx_genres_path ON genres(path);

-- Parent index for direct-children queries.
CREATE INDEX idx_genres_parent ON genres(parent_id);

-- Revision index for SyncableRepository pull-since queries.
CREATE INDEX idx_genres_revision ON genres(revision);

-- ── book_genres ───────────────────────────────────────────────────────────────
-- Cross-user junction; one shared genre set per book. Cascade-deletes on either
-- side via FK. No SyncableTable substrate — book updates carry their genre set
-- inline via BookSyncPayload.genres, so the junction itself doesn't need its own
-- revision cursor.
CREATE TABLE book_genres (
    book_id  TEXT NOT NULL,
    genre_id TEXT NOT NULL,
    PRIMARY KEY (book_id, genre_id),
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE,
    FOREIGN KEY (genre_id) REFERENCES genres(id) ON DELETE CASCADE
);

CREATE INDEX idx_book_genres_genre ON book_genres(genre_id);

-- ── genre_aliases ─────────────────────────────────────────────────────────────
-- Maps raw scanner strings to canonical genre ids; case-insensitive lookup via
-- COLLATE NOCASE on the PK. Used at scan ingest (resolve `AnalyzedBook.genres`
-- entries) and at curator merge (repoint aliases from a merged-away source).
CREATE TABLE genre_aliases (
    raw_string TEXT PRIMARY KEY COLLATE NOCASE,
    genre_id   TEXT NOT NULL,
    FOREIGN KEY (genre_id) REFERENCES genres(id) ON DELETE CASCADE
);

CREATE INDEX idx_genre_aliases_genre ON genre_aliases(genre_id);

-- ── pending_book_genres ───────────────────────────────────────────────────────
-- Raw scanner strings that didn't match any genre_aliases entry, keyed back to
-- the originating book. Curator screen aggregates by raw_string and maps to a
-- canonical genre, which backfills book_genres for every linked book.
-- Idempotent on rescan: scanner wipes-then-rewrites this table per book.
CREATE TABLE pending_book_genres (
    book_id       TEXT    NOT NULL,
    raw_string    TEXT    NOT NULL COLLATE NOCASE,
    first_seen_at INTEGER NOT NULL,
    PRIMARY KEY (book_id, raw_string),
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
);

CREATE INDEX idx_pending_book_genres_raw ON pending_book_genres(raw_string);

-- ── book_search: extend with genres column ────────────────────────────────────
-- book_search is contentless (content='') with contentless_delete=1; the
-- application layer owns all FTS population via BookRepository +
-- BookSearchReindexer. No triggers exist on book_search. The existing columns
-- (title, subtitle, description, contributor_names, series_names, tags) are
-- preserved verbatim; genres is appended.
-- DESTRUCTIVE: book_search FTS5 dropped to add genres column; application will
-- repopulate on the next scan or explicit reindex call.
DROP TABLE book_search;
CREATE VIRTUAL TABLE book_search USING fts5(
    title,
    subtitle,
    description,
    contributor_names,
    series_names,
    tags,
    genres,
    content='',
    contentless_delete=1,
    tokenize='unicode61 remove_diacritics 2'
);
