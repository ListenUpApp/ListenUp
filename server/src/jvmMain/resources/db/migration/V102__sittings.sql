-- One row per sitting: a person's unbroken time with one book (RealListen.SITTING_GAP_MS), the row the Activity
-- feed shows for listening and the source of Discover's "recently listened". Written only by the server, by
-- SittingReconciler, as a projection of the evidence for one (user, book) — closed listening_events today. It
-- is rebuilt from that evidence whenever it changes, so a row grows in place and keeps its id; two rows bridged
-- by late evidence become one, the other tombstoned. Only a real listen (RealListen.THRESHOLD_MS of listening,
-- time two devices played at once counted once) has a row. Never read by stats or streaks.
--
-- began_read is 'none', 'first' or 'reread': whether the book's started announcement falls in this sitting.
-- finished_read is 1 when its finished announcement does. Synced to everyone who can open the book.
CREATE TABLE sittings (
    id            TEXT    NOT NULL PRIMARY KEY,
    user_id       TEXT    NOT NULL,
    book_id       TEXT    NOT NULL,
    started_at    INTEGER NOT NULL,
    ended_at      INTEGER NOT NULL,
    listened_ms   INTEGER NOT NULL,
    began_read    TEXT    NOT NULL DEFAULT 'none',
    finished_read INTEGER NOT NULL DEFAULT 0,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL,
    revision      INTEGER NOT NULL,
    deleted_at    INTEGER,
    client_op_id  TEXT
);

CREATE INDEX idx_sittings_user_book ON sittings(user_id, book_id);
CREATE INDEX idx_sittings_ended_at ON sittings(ended_at);
CREATE INDEX idx_sittings_revision ON sittings(revision);
