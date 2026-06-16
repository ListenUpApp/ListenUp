-- Per-user home timezone (IANA) for stats day-boundary math (streaks/leaderboard). Sourced from the
-- device via login + live events; never from imports. No backfill — existing rows default to UTC and
-- self-correct on the next login/live event.
ALTER TABLE users ADD COLUMN timezone TEXT NOT NULL DEFAULT 'UTC';
