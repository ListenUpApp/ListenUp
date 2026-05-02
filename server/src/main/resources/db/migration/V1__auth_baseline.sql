-- Auth baseline: users + sessions tables.
-- Schema is designed fresh per migration_philosophy — not transcribed from goose.

CREATE TABLE users (
    id                  TEXT PRIMARY KEY,
    email               TEXT NOT NULL,
    email_normalized    TEXT NOT NULL UNIQUE,
    password_hash       TEXT NOT NULL,
    role                TEXT NOT NULL CHECK (role IN ('root', 'admin', 'member')),
    display_name        TEXT NOT NULL,
    status              TEXT NOT NULL CHECK (status IN ('active', 'pending_approval', 'denied')),
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER NOT NULL,
    last_login_at       INTEGER
);

CREATE TABLE sessions (
    id                  TEXT PRIMARY KEY,
    user_id             TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash  TEXT NOT NULL,
    family_id           TEXT NOT NULL,
    previous_hash       TEXT,
    label               TEXT,
    user_agent          TEXT,
    created_at          INTEGER NOT NULL,
    expires_at          INTEGER NOT NULL,
    last_used_at        INTEGER NOT NULL,
    revoked_at          INTEGER
);

CREATE INDEX idx_sessions_user_active   ON sessions(user_id, revoked_at);
CREATE INDEX idx_sessions_family        ON sessions(family_id);
CREATE INDEX idx_sessions_expires       ON sessions(expires_at);
