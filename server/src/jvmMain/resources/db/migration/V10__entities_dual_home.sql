-- entities/entity_bio_entries shipped in V9 with no UI writer; guaranteed empty;
-- rebuilt for the dual-home + unified-log design (2026-07-15 spec).
--
-- Story World entities are now dual-homed: every entity is scoped under exactly
-- one of home_series_id (a series) or home_book_id (a standalone book with no
-- series) — never both, never neither. entity_bio_entries is dropped outright:
-- the spoiler-anchored biography model it carried is superseded by the
-- unified-log design and has no surviving rows to migrate.

DROP TABLE entity_bio_entries;
DROP TABLE entities;

CREATE TABLE entities (
    id              TEXT    NOT NULL,
    kind            TEXT    NOT NULL,
    home_series_id  TEXT    REFERENCES book_series(id),
    home_book_id    TEXT    REFERENCES books(id),
    name            TEXT    NOT NULL,
    image_ref       TEXT,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    revision        INTEGER NOT NULL,
    deleted_at      INTEGER,
    client_op_id    TEXT,
    PRIMARY KEY (id),
    CHECK ((home_series_id IS NULL) != (home_book_id IS NULL))
);

CREATE INDEX idx_entities_home_series ON entities(home_series_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_entities_home_book ON entities(home_book_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_entities_revision ON entities(revision);
