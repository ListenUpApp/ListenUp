-- Invite system. An admin issues a single-use invite (unique code) addressed to an
-- email; claiming it provisions a user with the invited role. claimed_at/claimed_by
-- stay NULL until redemption, recording when and by whom the invite was consumed.
-- invited_by backlinks a user to the invite's issuer, preserving the invitation graph.
CREATE TABLE invites (
    id           TEXT    NOT NULL,
    code         TEXT    NOT NULL UNIQUE,
    email        TEXT    NOT NULL,
    display_name TEXT    NOT NULL,
    role         TEXT    NOT NULL,
    created_by   TEXT    NOT NULL,
    expires_at   INTEGER NOT NULL,
    claimed_at   INTEGER,
    claimed_by   TEXT,
    created_at   INTEGER NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE users ADD COLUMN invited_by TEXT;
