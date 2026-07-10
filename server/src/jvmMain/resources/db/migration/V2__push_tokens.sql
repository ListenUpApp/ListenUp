-- V2__push_tokens.sql — device push-registration rows (#604).
--
-- FK lives here only (SQLDelight can't resolve cross-.sq refs, so PushTokens.sq omits it) —
-- ON DELETE CASCADE is documentation/defense, not relied upon: the authoritative cleanup path
-- is SessionService.deleteExpired's deleteOrphaned sweep, since FK enforcement is not guaranteed
-- on for every deployment.

CREATE TABLE push_tokens (
    token      TEXT    NOT NULL PRIMARY KEY,
    platform   TEXT    NOT NULL,
    session_id TEXT    NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    user_id    TEXT    NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX idx_push_tokens_user    ON push_tokens(user_id);
CREATE INDEX idx_push_tokens_session ON push_tokens(session_id);
