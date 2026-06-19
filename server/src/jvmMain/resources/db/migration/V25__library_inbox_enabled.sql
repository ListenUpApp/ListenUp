-- Per-library inbox gate. When enabled, a newly-scanned book lands in the
-- library's inbox (hidden from members, visible to admins) pending triage
-- rather than becoming immediately public. Off by default — sharing is the
-- default, so existing libraries are unaffected and new books stay public.
ALTER TABLE libraries ADD COLUMN inbox_enabled INTEGER NOT NULL DEFAULT 0;
