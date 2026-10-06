-- match_receipts (matching redesign, PR 3): what one metadata match changed, so it can be undone.
--
--   id:             the undo token.
--   entity_kind:    'book' today; 'contributor' arrives with the people PR, on this same table.
--   applied_by:     the user who applied the match.
--   revision_after: the entity's revision right after the apply. Undo is refused once the entity has moved on.
--   snapshot:       JSON — the pre-apply state of everything the apply touched (for a book: the whole payload,
--                   the cover columns and the mood links it changed). The orphan-image sweep treats a cover
--                   path named here as live while the receipt is.
--   changes:        JSON — List<AppliedChange>, what the receipt and "See what changed" show.
--   undone_at:      set by Undo. A receipt is live while it is NULL.
-- One live receipt per entity: a new match replaces the old one. No FK: entity_kind makes the owner
-- polymorphic, and a daily sweep deletes receipts that can no longer be undone.

CREATE TABLE match_receipts (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    entity_kind    TEXT        NOT NULL,
    entity_id      VARCHAR(36) NOT NULL,
    applied_by     VARCHAR(36) NOT NULL,
    applied_at     INTEGER     NOT NULL,
    revision_after INTEGER     NOT NULL,
    snapshot       TEXT        NOT NULL,
    changes        TEXT        NOT NULL,
    undone_at      INTEGER
);

CREATE UNIQUE INDEX match_receipts_live ON match_receipts (entity_kind, entity_id) WHERE undone_at IS NULL;
