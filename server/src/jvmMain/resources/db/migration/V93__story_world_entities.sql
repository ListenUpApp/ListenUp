-- Story World, PR A: entities, their shared edit history, the series-merge snapshot of re-homed
-- entities, and the two Story World permissions.
--
-- entities: library-shared world data (character, location, item, group, people, event, concept).
--   Exactly one home: a series (home_series_id) or a book (home_book_id). `kind` is stored lowercase.
--   updated_at is the server's clock at the last write; writes apply in arrival order.
--   home_book_id cascades on a HARD book delete (a soft removal tombstones entities through
--   BookRepository.softDelete instead); parent_id is nulled if a parent row is ever hard-deleted.
-- story_world_history: one row per write, written in the same transaction. Kept forever.
--   target_type is 'entity' here; world events (PR B) share the table. revision is the global sync
--   revision the change committed at, which orders rows that share a millisecond.
-- series_merge_receipt_entities: which entities a series merge re-homed, so its undo can move them back.
CREATE TABLE entities (
    id              TEXT    NOT NULL PRIMARY KEY,
    kind            TEXT    NOT NULL,
    name            TEXT    NOT NULL,
    descriptor      TEXT,
    parent_id       TEXT    REFERENCES entities(id) ON DELETE SET NULL,
    home_series_id  TEXT    REFERENCES book_series(id),
    home_book_id    TEXT    REFERENCES books(id) ON DELETE CASCADE,
    image_ref       TEXT,
    created_by      TEXT,
    updated_by      TEXT,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    revision        INTEGER NOT NULL,
    deleted_at      INTEGER,
    client_op_id    TEXT,
    CHECK ((home_series_id IS NULL) != (home_book_id IS NULL))
);

CREATE INDEX idx_entities_home_series ON entities(home_series_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_entities_home_book ON entities(home_book_id);
CREATE INDEX idx_entities_parent ON entities(parent_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_entities_revision ON entities(revision);

CREATE TABLE story_world_history (
    id           TEXT    NOT NULL PRIMARY KEY,
    target_type  TEXT    NOT NULL,
    target_id    TEXT    NOT NULL,
    actor_id     TEXT,
    occurred_at  INTEGER NOT NULL,
    revision     INTEGER NOT NULL,
    op           TEXT    NOT NULL,
    before_json  TEXT,
    after_json   TEXT
);

CREATE INDEX idx_story_world_history_target ON story_world_history(target_type, target_id, revision);

CREATE TABLE series_merge_receipt_entities (
    receipt_id TEXT NOT NULL REFERENCES series_merge_receipts(id) ON DELETE CASCADE,
    entity_id  TEXT NOT NULL REFERENCES entities(id) ON DELETE CASCADE,
    PRIMARY KEY (receipt_id, entity_id)
);

ALTER TABLE users ADD COLUMN can_contribute_story_world INTEGER NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN can_curate_story_world INTEGER NOT NULL DEFAULT 0;
