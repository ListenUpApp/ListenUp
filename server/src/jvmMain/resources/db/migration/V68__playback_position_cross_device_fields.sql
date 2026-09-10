ALTER TABLE playback_positions ADD COLUMN finished_at INTEGER;
ALTER TABLE playback_positions ADD COLUMN has_custom_speed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE playback_positions ADD COLUMN has_custom_boost INTEGER NOT NULL DEFAULT 0;
