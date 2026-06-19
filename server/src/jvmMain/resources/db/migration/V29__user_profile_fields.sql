-- V29: profile fields — tagline + avatar type (Go-parity profiles).
ALTER TABLE users ADD COLUMN tagline TEXT;
ALTER TABLE users ADD COLUMN avatar_type TEXT NOT NULL DEFAULT 'auto';
