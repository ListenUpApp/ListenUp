-- Pre-auth "watch tokens" (#1068): a device waiting on an admin decision (pending
-- registration today; password reset next) registers its push token against the flow's
-- unguessable handle instead of a session — the waiter has no session by definition.
-- Rows die on decision (evicted after the decision push) or by TTL sweep.
CREATE TABLE push_watch_tokens (
    token      TEXT    NOT NULL PRIMARY KEY,
    platform   TEXT    NOT NULL,
    watch_kind TEXT    NOT NULL,
    watch_key  TEXT    NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE INDEX idx_push_watch_tokens_key ON push_watch_tokens(watch_kind, watch_key);
