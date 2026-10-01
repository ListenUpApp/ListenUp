-- Hardcover history backfill (#1540): send the books finished in ListenUp before connecting. Server-only;
-- a client sees it as HardcoverConnection.Connected.history.
--
-- hardcover_history: one row per user — the offer and how far it has got.
--   hc_user_id: the Hardcover account it was offered for; connecting another account replaces the row.
--   state: OFFERED (the card), DECLINED ("Not now": the quiet row), SENDING, DONE, DISMISSED.
--   total_books: how many books the current send set out with.
--   Survives a disconnect, like the ledger; a reconnect to the same account keeps it.
-- hardcover_history_reads: the ledger. One row per history read handled — SENT, or ALREADY_THERE when
--   Hardcover already held it. Goes with its book_reads row, and with its user.
-- hardcover_outbox gains history_read_id: the book_reads row a HISTORY row sends; NULL for live rows. It is
--   how "not already queued" is asked without parsing the payload.
-- hardcover_pushed_reads gains origin: LIVE (a listen-through wrote the read; every existing row) or HISTORY
--   (a HISTORY row did). A read ListenUp pushed live never counts as an earlier read when history is sent.
--
-- Every connection that already has own reads from before it connected is offered them once: the
-- listeners connected before this shipped are the ones it is for.
--
-- FKs live here only: SQLDelight can't resolve cross-.sq references, so the .sq files omit them.

CREATE TABLE hardcover_history (
    user_id     TEXT    NOT NULL PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    hc_user_id  INTEGER NOT NULL,
    state       TEXT    NOT NULL,
    total_books INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

CREATE TABLE hardcover_history_reads (
    user_id     TEXT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_id     TEXT    NOT NULL REFERENCES book_reads(id) ON DELETE CASCADE,
    outcome     TEXT    NOT NULL,
    recorded_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, read_id)
);

CREATE INDEX idx_hardcover_history_reads_read ON hardcover_history_reads(read_id);

ALTER TABLE hardcover_outbox ADD COLUMN history_read_id TEXT;

CREATE INDEX idx_hardcover_outbox_history_read ON hardcover_outbox(user_id, history_read_id);

ALTER TABLE hardcover_pushed_reads ADD COLUMN origin TEXT NOT NULL DEFAULT 'LIVE';

INSERT INTO hardcover_history (user_id, hc_user_id, state, total_books, updated_at)
SELECT c.user_id, c.hc_user_id, 'OFFERED', 0, c.connected_at
FROM hardcover_connections AS c
WHERE EXISTS (
    SELECT 1 FROM book_reads AS r
    JOIN books AS b ON b.id = r.book_id AND b.deleted_at IS NULL
    WHERE r.user_id = c.user_id AND r.source <> 'hardcover' AND r.finished_at < c.connected_at
);
