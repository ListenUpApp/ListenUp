-- Story World entities: library-shared, curated world data (characters, locations,
-- items) namespaced under a series. Unlike reading_orders, these are NOT user-owned
-- — any caller holding the metadata-edit permission may write them, matching the
-- series/genre/tag curation model, not the per-user reading-order model.
--
-- entity_bio_entries is a child collection carried whole-aggregate with its parent
-- (mirrors book_chapters' delete-then-insert replace pattern): each entry is a
-- spoiler-anchored biography fragment, optionally anchored to a (book, position).
-- Column set matches Entities.sq / EntityBioEntries.sq exactly.

CREATE TABLE entities (
    id              TEXT    NOT NULL,
    kind            TEXT    NOT NULL,
    home_series_id  TEXT    NOT NULL REFERENCES book_series(id),
    name            TEXT    NOT NULL,
    image_ref       TEXT,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    revision        INTEGER NOT NULL,
    deleted_at      INTEGER,
    client_op_id    TEXT,
    PRIMARY KEY (id)
);

CREATE INDEX idx_entities_home_series ON entities(home_series_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_entities_revision ON entities(revision);

CREATE TABLE entity_bio_entries (
    id           TEXT    NOT NULL,
    entity_id    TEXT    NOT NULL,
    book_id      TEXT,
    position_ms  INTEGER,
    mode         TEXT    NOT NULL,
    text         TEXT    NOT NULL,
    sort_key     INTEGER NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (entity_id) REFERENCES entities(id) ON DELETE CASCADE
);

CREATE INDEX idx_entity_bio_entries_entity ON entity_bio_entries(entity_id);
