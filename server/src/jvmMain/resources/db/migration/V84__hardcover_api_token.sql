-- The admin's Hardcover API token (#1542). Server-only; a client only ever sees HardcoverSourceStatus,
-- which describes the token by its owner and never carries it.
--
-- hardcover_api_token: at most one row (id is always 1).
--   token_enc: the token sealed by HardcoverTokenCipher (AES-256-GCM, key derived from the JWT secret),
--     so a leaked database or backup alone does not expose it.
--   hc_username: whose Hardcover account it is, checked with `me` before it was stored.
--   set_at: when it was stored (epoch ms).
--   rejected_at: when Hardcover later answered 401 for it. Catalogue reads then fall back to connected
--     accounts until the admin replaces it; replacing clears it.
-- No FKs: the token belongs to the server, not to a user.

CREATE TABLE hardcover_api_token (
    id          INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
    token_enc   TEXT    NOT NULL,
    hc_username TEXT    NOT NULL,
    set_at      INTEGER NOT NULL,
    rejected_at INTEGER
);
