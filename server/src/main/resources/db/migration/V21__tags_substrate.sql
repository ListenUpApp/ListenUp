-- Tags substrate: slug identity column, book_tags junction, book_search reshape,
-- tag_search FTS5. Per no-existing-users policy, tags is reset before gaining the
-- new NOT NULL slug column; book_search is recreated to add the embedded tags column.

-- DESTRUCTIVE: tags table contents nuked per no-existing-users policy.
-- Slug becomes a UNIQUE NOT NULL identity column added in the same migration.
DELETE FROM tags;

ALTER TABLE tags ADD COLUMN slug TEXT NOT NULL DEFAULT '';
CREATE UNIQUE INDEX idx_tags_slug ON tags(slug) WHERE deleted_at IS NULL;

-- book_tags junction (global, syncable): one book has one shared tag set.
-- Composite PK (book_id, tag_id) is the natural key; `id` column stores the
-- "$bookId:$tagId" synthetic key used by the SyncableRepository substrate for
-- revision-cursor queries, catch-up pagination, and the sync registry.
-- Soft-deletes via deleted_at; revision-tracked for the catch-up sync protocol.
CREATE TABLE book_tags (
    id           TEXT    NOT NULL,
    book_id      TEXT    NOT NULL,
    tag_id       TEXT    NOT NULL,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    revision     INTEGER NOT NULL,
    deleted_at   INTEGER,
    client_op_id TEXT,
    PRIMARY KEY (book_id, tag_id),
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_book_tags_id ON book_tags(id);
CREATE INDEX idx_book_tags_tag_id ON book_tags(tag_id, book_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_book_tags_revision ON book_tags(revision);

-- Recreate book_search FTS5 with an embedded tags column.
-- book_search is contentless (content='') with contentless_delete=1; the application
-- layer owns all FTS population via BookRepository + BookSearchReindexer. No triggers
-- exist on book_search — only on the content-backed contributor_search / series_search.
-- The existing columns (title, subtitle, description, contributor_names, series_names)
-- are preserved verbatim; tags is appended so existing rowid mappings remain valid.
-- DESTRUCTIVE: book_search FTS5 dropped to add tags column; application will repopulate
-- on the next scan or explicit reindex call.
DROP TABLE book_search;
CREATE VIRTUAL TABLE book_search USING fts5(
    title,
    subtitle,
    description,
    contributor_names,
    series_names,
    tags,
    content='',
    contentless_delete=1,
    tokenize='unicode61 remove_diacritics 2'
);

-- tag_search FTS5 — content-backed, mirrors contributor_search / series_search from V19.
-- Triggers keep the shadow index in sync with the tags table.
CREATE VIRTUAL TABLE tag_search USING fts5(
    name,
    slug,
    content='tags',
    content_rowid='rowid',
    tokenize='unicode61 remove_diacritics 2'
);

CREATE TRIGGER tags_ai AFTER INSERT ON tags BEGIN
    INSERT INTO tag_search(rowid, name, slug) VALUES (new.rowid, new.name, new.slug);
END;

CREATE TRIGGER tags_au AFTER UPDATE ON tags BEGIN
    INSERT INTO tag_search(tag_search, rowid, name, slug) VALUES ('delete', old.rowid, old.name, old.slug);
    INSERT INTO tag_search(rowid, name, slug) VALUES (new.rowid, new.name, new.slug);
END;

CREATE TRIGGER tags_ad AFTER DELETE ON tags BEGIN
    INSERT INTO tag_search(tag_search, rowid, name, slug) VALUES ('delete', old.rowid, old.name, old.slug);
END;

-- Backfill tag_search from any existing rows (empty after the DELETE above in this
-- migration, but the backfill is idempotent and safe for future re-runs).
INSERT INTO tag_search(rowid, name, slug)
    SELECT rowid, name, slug FROM tags WHERE deleted_at IS NULL;
