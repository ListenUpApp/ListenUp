-- V45: windowed Books-finished and Streak leaderboard metrics on the public projection.
--
-- Adds trailing 7/30/365-day "books finished" counts and "longest streak within the window"
-- values to public_profiles, mirroring the existing windowed seconds columns. They are computed
-- live by PublicProfileMaintainer.refresh() (like total_seconds_last_365_days) — books from
-- book_reads.finished_at in the window, streak from the longest consecutive listening-day run
-- whose events fall inside the window. Existing rows default to 0 until their next refresh.
ALTER TABLE public_profiles ADD COLUMN books_finished_last_7_days   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public_profiles ADD COLUMN books_finished_last_30_days  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public_profiles ADD COLUMN books_finished_last_365_days INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public_profiles ADD COLUMN longest_streak_last_7_days   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public_profiles ADD COLUMN longest_streak_last_30_days  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public_profiles ADD COLUMN longest_streak_last_365_days INTEGER NOT NULL DEFAULT 0;
