-- world_events: the Story World unified event log — the single log every world
-- happening lives in (manual notes, scene entries, departures, and reserved typed
-- vocabulary awaiting Arc 3 reducers). Syncable, soft-deletable, revision-tracked, the
-- same discipline as entities. Dual-homed under exactly one of home_series_id /
-- home_book_id (a namespacing key only, mirroring entities' dual-home rule), and
-- optionally anchored to a specific book position via (book_id, position_ms) — a null
-- book_id means the event has no book anchor and is always visible. `type` and `source`
-- are stored lowercase, matching entities.kind's enum-column convention.
-- Column set matches WorldEventSyncPayload / WorldEvents.sq exactly.
CREATE TABLE world_events (
    id                 TEXT    NOT NULL,
    home_series_id     TEXT    REFERENCES book_series(id),
    home_book_id       TEXT    REFERENCES books(id),
    book_id            TEXT    REFERENCES books(id),
    position_ms        INTEGER,
    type               TEXT    NOT NULL,
    text               TEXT    NOT NULL,
    subject_entity_id  TEXT    REFERENCES entities(id),
    object_entity_id   TEXT    REFERENCES entities(id),
    source             TEXT    NOT NULL,
    track_id           TEXT,
    track_version      INTEGER,
    created_at         INTEGER NOT NULL,
    updated_at         INTEGER NOT NULL,
    revision           INTEGER NOT NULL,
    deleted_at         INTEGER,
    client_op_id       TEXT,
    PRIMARY KEY (id),
    CHECK ((home_series_id IS NULL) != (home_book_id IS NULL))
);

CREATE INDEX idx_world_events_book_position ON world_events(book_id, position_ms);
CREATE INDEX idx_world_events_home_series ON world_events(home_series_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_world_events_home_book ON world_events(home_book_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_world_events_revision ON world_events(revision);

-- world_event_mentions: the server-recomputed junction between a world event and every
-- entity it mentions (text-embedded @entity tokens plus subject/object) — never
-- client-authored, always replaced wholesale on every event write inside the same
-- transaction as the event's own root-row write. No revision/sync columns of its own:
-- it rides the parent event's sync-discipline entirely, mirroring the entity_bio_entries
-- child-collection pattern (V9)'s delete-then-insert replace.
CREATE TABLE world_event_mentions (
    event_id   TEXT NOT NULL,
    entity_id  TEXT NOT NULL,
    PRIMARY KEY (event_id, entity_id),
    FOREIGN KEY (event_id) REFERENCES world_events(id) ON DELETE CASCADE,
    FOREIGN KEY (entity_id) REFERENCES entities(id)
);

CREATE INDEX idx_world_event_mentions_entity ON world_event_mentions(entity_id);
