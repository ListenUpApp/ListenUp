-- Structured client device metadata on auth sessions (MD-auth). Additive, forward-only.
ALTER TABLE sessions ADD COLUMN device_type TEXT;
ALTER TABLE sessions ADD COLUMN platform TEXT;
ALTER TABLE sessions ADD COLUMN platform_version TEXT;
ALTER TABLE sessions ADD COLUMN client_name TEXT;
ALTER TABLE sessions ADD COLUMN client_version TEXT;
ALTER TABLE sessions ADD COLUMN device_name TEXT;
ALTER TABLE sessions ADD COLUMN device_model TEXT;
