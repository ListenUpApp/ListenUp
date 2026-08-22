-- V63: per-user notification inbox + per-type delivery preferences.
--
-- No FK on user_id -- SQLDelight cannot express cross-.sq references and the .sq definition
-- must match this file exactly. Rows are bounded by retention pruning (NotificationEmitter)
-- and swept on user deletion like every other per-user aggregate.
CREATE TABLE notifications (
    id           TEXT    NOT NULL PRIMARY KEY,
    user_id      TEXT    NOT NULL,
    type         TEXT    NOT NULL,
    payload      TEXT    NOT NULL,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    read_at      INTEGER,
    revision     INTEGER NOT NULL,
    deleted_at   INTEGER,
    client_op_id TEXT
);

CREATE INDEX idx_notifications_user ON notifications(user_id, created_at);
CREATE INDEX idx_notifications_revision ON notifications(revision);

CREATE TABLE notification_prefs (
    user_id    TEXT    NOT NULL,
    type       TEXT    NOT NULL,
    in_app     INTEGER NOT NULL,
    push       INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, type)
);
