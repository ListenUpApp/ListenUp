-- Admin user roster: an admin-only, global projection of every ACTIVE or PENDING_APPROVAL
-- user, carrying exactly the fields the admin Users/pending lists render. Syncable,
-- soft-deletable, revision-tracked. Maintained server-side; clients never write it.
CREATE TABLE admin_user_roster (
    id                 TEXT    NOT NULL,
    email              TEXT    NOT NULL,
    display_name       TEXT    NOT NULL,
    role               TEXT    NOT NULL,
    status             TEXT    NOT NULL,
    can_share          INTEGER NOT NULL,
    account_created_at INTEGER NOT NULL,
    created_at         INTEGER NOT NULL,
    updated_at         INTEGER NOT NULL,
    revision           INTEGER NOT NULL,
    deleted_at         INTEGER,
    client_op_id       TEXT,
    PRIMARY KEY (id)
);
CREATE INDEX idx_admin_user_roster_revision ON admin_user_roster(revision);
