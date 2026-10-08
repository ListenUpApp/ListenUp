-- V92__curate_library_permission.sql — split "Can edit" into Edit metadata and Curate library.
-- Curate library is the merge, unmerge and delete of contributors, series, genres, tags and moods,
-- and undoing those merges. Until now can_edit carried it, so every user who holds can_edit today
-- keeps it: the new column is backfilled from can_edit. A user created afterwards gets the default,
-- off — destructive, library-wide work defaults off.
ALTER TABLE users ADD COLUMN can_curate_library INTEGER NOT NULL DEFAULT 0;
UPDATE users SET can_curate_library = can_edit;
ALTER TABLE admin_user_roster ADD COLUMN can_curate_library INTEGER NOT NULL DEFAULT 0;
UPDATE admin_user_roster SET can_curate_library = can_edit;
