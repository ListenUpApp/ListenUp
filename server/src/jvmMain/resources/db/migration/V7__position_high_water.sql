-- High-water listening frontier (Integration Foundations spec §4): the furthest position
-- ever heard in a book, the spoiler-safe frontier for Story World Stage 3. Backfilled from
-- position_ms — the safe-set definition already includes all of any `finished` book, so
-- persisting `duration` into finished rows would be redundant.

ALTER TABLE playback_positions ADD COLUMN max_position_ms INTEGER NOT NULL DEFAULT 0;

UPDATE playback_positions SET max_position_ms = position_ms;
