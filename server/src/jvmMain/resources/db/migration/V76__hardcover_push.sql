-- Hardcover push and matching (#601 B2 + B4). Server-only; nothing here is ever synced to a client.
--
-- A listen-through is identified by its started_at: listen_throughs keeps one row per (user, book)
-- and has no other key. 0 stands for a book already in progress before listen-throughs existed.
--
-- hardcover_book_links: one row per (user, book) ListenUp has tried to match on Hardcover.
--   match_state LINKED or NEEDS_MATCH. A NEEDS_MATCH row never guesses: it parks the book's pushes
--   until the user links it by hand, and its hc_book_id / hc_edition_id / match_method are NULL.
--   hc_user_book_id and open_hc_read_id are the Hardcover records ListenUp writes to;
--   open_read_listen_through_started_at is the listen-through that open read serves.
--   suppressed_listen_through_started_at is set by the deletion rule (the user deleted this book or
--   read on Hardcover), so that listen-through pushes nothing more; a new listen-through clears it.
--   last_progress_pushed_at drives the PROGRESS throttle (one per sitting gap per book).
-- hardcover_outbox: pushes waiting for Hardcover, drained in id order on one lane per user.
-- hardcover_pushed_reads: every Hardcover read ListenUp opened or continued, so the pull (B3) can
--   tell its own pushes from reads logged elsewhere. No FK to books on purpose: a book removed and
--   re-added under a new id must not turn ListenUp's own reads into "read on Hardcover".
-- hardcover_connections gains last_synced_at (the last successful push) and push_error (the error
--   that outlasted the retry cap), for the connection screen to show once PR 5 wires it.
--
-- FKs live here only: SQLDelight can't resolve cross-.sq references, so the .sq files omit them.

CREATE TABLE hardcover_book_links (
    user_id                              TEXT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    book_id                              TEXT    NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    hc_book_id                           INTEGER,
    hc_edition_id                        INTEGER,
    match_method                         TEXT,
    match_state                          TEXT    NOT NULL,
    hc_user_book_id                      INTEGER,
    open_hc_read_id                      INTEGER,
    open_read_listen_through_started_at  INTEGER,
    suppressed_listen_through_started_at INTEGER,
    last_progress_pushed_at              INTEGER,
    updated_at                           INTEGER NOT NULL,
    PRIMARY KEY (user_id, book_id)
);

CREATE INDEX idx_hardcover_book_links_book ON hardcover_book_links(book_id);

CREATE TABLE hardcover_outbox (
    id                        INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    user_id                   TEXT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    book_id                   TEXT    NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    listen_through_started_at INTEGER NOT NULL,
    op                        TEXT    NOT NULL,
    payload                   TEXT    NOT NULL,
    created_at                INTEGER NOT NULL,
    attempts                  INTEGER NOT NULL DEFAULT 0,
    next_attempt_at           INTEGER NOT NULL,
    last_error                TEXT
);

CREATE INDEX idx_hardcover_outbox_user_book ON hardcover_outbox(user_id, book_id, op);

CREATE TABLE hardcover_pushed_reads (
    user_id     TEXT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    hc_read_id  INTEGER NOT NULL,
    book_id     TEXT    NOT NULL,
    recorded_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, hc_read_id)
);

ALTER TABLE hardcover_connections ADD COLUMN last_synced_at INTEGER;
ALTER TABLE hardcover_connections ADD COLUMN push_error TEXT;
