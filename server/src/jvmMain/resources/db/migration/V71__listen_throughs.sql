-- Each user's current listen-through of each book. "Started reading" used to be announced on the
-- very first position write — an eight-second tap was enough. It now waits until the listen-through
-- holds a real listen (1 minute), and this row is where the server remembers when that listen-through
-- began and whether the real start has been announced yet. Server-only; never synced.
CREATE TABLE listen_throughs (
    user_id         VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    book_id         VARCHAR(36) NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    started_at      INTEGER     NOT NULL,
    is_reread       INTEGER     NOT NULL,
    real_started_at INTEGER,
    PRIMARY KEY (user_id, book_id)
);
