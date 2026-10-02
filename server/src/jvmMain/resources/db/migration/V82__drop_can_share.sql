-- V82__drop_can_share.sql — remove the "Can share" per-user permission.
-- Since only admins write collections (#1548), can_share gated nothing: admins hold every
-- permission implicitly and members cannot reach the share path at all. The admin toggle
-- that set it was a control that did nothing, so the permission goes everywhere.
-- AdminUserRosterSyncPayload still emits canShare = true on the wire as a compat shim for
-- un-updated admin clients; that value is a constant and needs no column.
-- DESTRUCTIVE: removing the inert can_share permission; its values had no effect, so dropping them loses nothing.
ALTER TABLE users DROP COLUMN can_share;
ALTER TABLE admin_user_roster DROP COLUMN can_share;
