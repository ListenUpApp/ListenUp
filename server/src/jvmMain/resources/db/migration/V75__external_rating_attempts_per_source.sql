-- external_rating_attempts becomes per (book, source).
--
-- Keyed by book alone, one attempt row answered "has ANY source tried this book?", so a book Audible
-- had already tried never reached a source added later: the backfill picks books with no attempt, and
-- every book on a server upgraded from Audible-only ratings already had one. Hardcover and Goodreads
-- would only reach those books through the nightly 1/30th rotation, up to a month later, and the same
-- would happen for every future source. Keyed by (book, source), the backfill asks the right
-- question: which runnable source has never tried this book?
--
-- Every existing row was written by the Audible-only fetcher, so it becomes an AUDIBLE attempt. The
-- join on books guards the rebuilt table's foreign key against a row whose book is already gone.
--
-- The old table is renamed ASIDE and the new one created under the real name, for the reason V62
-- gives: renaming a `_new` table onto the real name leaves the name quoted in sqlite_master.

ALTER TABLE external_rating_attempts RENAME TO external_rating_attempts_old;

CREATE TABLE external_rating_attempts (
    book_id      TEXT    NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    source       TEXT    NOT NULL,
    attempted_at INTEGER NOT NULL,
    PRIMARY KEY (book_id, source)
);

INSERT INTO external_rating_attempts (book_id, source, attempted_at)
SELECT a.book_id, 'AUDIBLE', a.attempted_at
FROM external_rating_attempts_old a
JOIN books b ON b.id = a.book_id;

DROP TABLE external_rating_attempts_old;
