-- V57: password reset requests. Server-owned, non-syncable, short-lived.
--
-- Deliberately NOT a status flip on the users row: a requester is ACTIVE and must stay
-- ACTIVE throughout, because they may be signed in on another device mid-book. A pending
-- request must disturb nothing until it completes.
--
-- No FK on user_id / decided_by — SQLDelight cannot express cross-.sq references and the
-- .sq definition must match this file exactly. Rows are swept on user deletion and expire
-- within minutes regardless.
CREATE TABLE password_reset_requests (
    id                TEXT    NOT NULL PRIMARY KEY,
    user_id           TEXT    NOT NULL,
    requested_at      INTEGER NOT NULL,
    expires_at        INTEGER NOT NULL,
    status            TEXT    NOT NULL,
    device_claim_hash TEXT    NOT NULL,
    code_hash         TEXT,
    attempts          INTEGER NOT NULL DEFAULT 0,
    decided_by        TEXT,
    decided_at        INTEGER
);

CREATE INDEX idx_password_reset_requests_status ON password_reset_requests(status, expires_at);
CREATE INDEX idx_password_reset_requests_user ON password_reset_requests(user_id);
