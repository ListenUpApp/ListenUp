-- V46: track when a user's avatar bytes last changed, so clients can force-refresh the cached image.
ALTER TABLE users           ADD COLUMN avatar_updated_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public_profiles ADD COLUMN avatar_updated_at INTEGER NOT NULL DEFAULT 0;
UPDATE public_profiles SET avatar_updated_at = updated_at;
