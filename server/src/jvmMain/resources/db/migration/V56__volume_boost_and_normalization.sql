ALTER TABLE playback_positions ADD COLUMN volume_boost_db REAL NOT NULL DEFAULT 0.0;
ALTER TABLE playback_positions ADD COLUMN measured_gain_db REAL;
ALTER TABLE user_settings ADD COLUMN default_volume_boost_db REAL NOT NULL DEFAULT 0.0;
ALTER TABLE books ADD COLUMN normalization_gain_db REAL;
