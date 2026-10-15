-- When the reader last dismissed this book's "just finished" card on Home, epoch ms; NULL when never.
-- It hides the card only for a finish at or before it, so a later re-read shows a fresh card. It only ever rises,
-- and it is merged apart from last_played_at: dismissing a card is not listening.
ALTER TABLE playback_positions ADD COLUMN finished_card_dismissed_at INTEGER;
