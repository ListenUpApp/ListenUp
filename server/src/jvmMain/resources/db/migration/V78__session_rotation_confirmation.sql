-- Lost-reply vs. replay: record whether the client received the latest rotation's reply.
--
-- rotated_at: when the current refresh token was issued (every rotation, grace re-rotations
--   included). last_used_at cannot serve: the grace branch deliberately leaves it anchored to the
--   last NORMAL rotation.
-- rotation_confirmed_at: when an access token minted at or after rotated_at was first used — proof
--   the rotation reply reached the client. NULL means the client may never have received it, so a
--   post-grace replay of the previous refresh token is read as a lost reply (re-rotate) rather than
--   theft (family revoke). See SessionService.rotate.
--
-- Existing rows are backfilled as confirmed: they keep today's revoke-on-late-replay behaviour
-- until their next rotation, rather than being opened to re-rotation by a deploy.
ALTER TABLE sessions ADD COLUMN rotated_at INTEGER;
ALTER TABLE sessions ADD COLUMN rotation_confirmed_at INTEGER;
UPDATE sessions SET rotated_at = last_used_at, rotation_confirmed_at = last_used_at;
