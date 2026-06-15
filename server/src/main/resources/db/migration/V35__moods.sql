-- Moods substrate: a first-class affective axis for books, mirroring the Tag stack
-- (flat, syncable, soft-delete). Genre / Mood / Tag are the three independent axes of
-- a book; Mood is the closest twin of Tag. Creates `moods` (catalog) + `book_moods`
-- (global junction). Purely additive — no existing table is touched.

-- moods catalog. `id` is the UUIDv7 storage identity; `slug` is the canonical
-- URL-safe identity derived from `name` at creation and immutable thereafter
-- (renames update `name` only). A partial unique index on `slug` (where
-- `deleted_at IS NULL`) enforces uniqueness among live moods while allowing slug
-- reuse after a mood is soft-deleted.
CREATE TABLE moods (
    id           TEXT    NOT NULL PRIMARY KEY,
    name         TEXT    NOT NULL,
    slug         TEXT    NOT NULL,
    revision     INTEGER NOT NULL,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    deleted_at   INTEGER,
    client_op_id TEXT
);

CREATE INDEX idx_moods_revision ON moods (revision);
CREATE INDEX idx_moods_name ON moods (name);
CREATE UNIQUE INDEX idx_moods_slug ON moods(slug) WHERE deleted_at IS NULL;

-- book_moods junction (global, syncable): one book has one shared mood set.
-- Composite PK (book_id, mood_id) is the natural key; `id` column stores the
-- "$bookId:$moodId" synthetic key used by the SyncableRepository substrate for
-- revision-cursor queries, catch-up pagination, and the sync registry.
-- Soft-deletes via deleted_at; revision-tracked for the catch-up sync protocol.
CREATE TABLE book_moods (
    id           TEXT    NOT NULL,
    book_id      TEXT    NOT NULL,
    mood_id      TEXT    NOT NULL,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    revision     INTEGER NOT NULL,
    deleted_at   INTEGER,
    client_op_id TEXT,
    PRIMARY KEY (book_id, mood_id),
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE,
    FOREIGN KEY (mood_id) REFERENCES moods(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_book_moods_id ON book_moods(id);
CREATE INDEX idx_book_moods_mood_id ON book_moods(mood_id, book_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_book_moods_revision ON book_moods(revision);
