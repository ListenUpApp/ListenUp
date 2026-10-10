-- Story World, PR B: world events (the lines of a world's log), the entities each one mentions, and the
-- series-merge snapshot of re-homed events.
--
-- world_events: library-shared, homed like entities (exactly one of home_series_id / home_book_id). An
--   optional anchor (book_id, position_ms) pins an event to a moment in one of its world's books; both or
--   neither are set, and the moment is never negative. `type` is stored lowercase. `detail`
--   is the type's qualifier (a JOINS role, a LEAVES "how"). Subject and object are entities of the same
--   world. updated_at is the server's clock at the last write; writes apply in arrival order.
--   home_book_id and book_id cascade on a HARD book delete; a soft removal tombstones the events through
--   WorldEventRepository.softDeleteAllForBook instead.
-- world_event_mentions: the entities of the event's world that it names (text tokens, subject, object),
--   recomputed by the server on every write in the same transaction. A tombstoned event keeps none.
-- History rows for events live in story_world_history (V93) with target_type 'world_event'.
-- series_merge_receipt_world_events: which events a series merge re-homed, so its undo can move them back.
CREATE TABLE world_events (
    id                 TEXT    NOT NULL PRIMARY KEY,
    home_series_id     TEXT    REFERENCES book_series(id),
    home_book_id       TEXT    REFERENCES books(id) ON DELETE CASCADE,
    book_id            TEXT    REFERENCES books(id) ON DELETE CASCADE,
    position_ms        INTEGER,
    type               TEXT    NOT NULL,
    text               TEXT    NOT NULL,
    detail             TEXT,
    subject_entity_id  TEXT    REFERENCES entities(id) ON DELETE SET NULL,
    object_entity_id   TEXT    REFERENCES entities(id) ON DELETE SET NULL,
    created_by         TEXT,
    updated_by         TEXT,
    created_at         INTEGER NOT NULL,
    updated_at         INTEGER NOT NULL,
    revision           INTEGER NOT NULL,
    deleted_at         INTEGER,
    client_op_id       TEXT,
    CHECK ((home_series_id IS NULL) != (home_book_id IS NULL)),
    CHECK ((book_id IS NULL) = (position_ms IS NULL)),
    CHECK (position_ms IS NULL OR position_ms >= 0)
);

CREATE INDEX idx_world_events_home_series ON world_events(home_series_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_world_events_home_book ON world_events(home_book_id);
CREATE INDEX idx_world_events_anchor ON world_events(book_id, position_ms);
CREATE INDEX idx_world_events_revision ON world_events(revision);

CREATE TABLE world_event_mentions (
    event_id   TEXT NOT NULL REFERENCES world_events(id) ON DELETE CASCADE,
    entity_id  TEXT NOT NULL REFERENCES entities(id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, entity_id)
);

CREATE INDEX idx_world_event_mentions_entity ON world_event_mentions(entity_id);

CREATE TABLE series_merge_receipt_world_events (
    receipt_id TEXT NOT NULL REFERENCES series_merge_receipts(id) ON DELETE CASCADE,
    event_id   TEXT NOT NULL REFERENCES world_events(id) ON DELETE CASCADE,
    PRIMARY KEY (receipt_id, event_id)
);
