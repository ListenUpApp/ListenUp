-- Series sequence becomes a number.
--
-- It was VARCHAR(64) but nothing downstream treated it as text: the write contract
-- (BookSeriesInput.position) has always been a Double, and the client parsed the string back to a
-- float to sort the library, to answer voice queries, and to save an edit. A value cast at every
-- use is not text, and the pretence cost correctness twice over — the client's `ORDER BY sequence`
-- sorted lexicographically, so book 10 came before book 2, and saving an edit ran the value through
-- `toDoubleOrNull()`, silently discarding anything that did not parse.
--
-- Free-form text still exists at the ingest edge (audio tags, scanner output, Audible/Audnexus
-- responses all stay String); it is parsed to a number exactly once, here and at persist time.
--
-- CONVERSION, and why it is not a bare CAST. SQLite's CAST is lenient in a way that would invent
-- data: CAST('Prequel' AS REAL) is 0.0, which would silently file an unnumbered volume as book 0 —
-- a wrong number is worse than no number, so anything that does not begin with a digit becomes
-- NULL. Values that DO begin with a digit convert on CAST's leading-numeric-prefix rule, which is
-- exactly the behaviour wanted for omnibus ranges: '1-3' and '1 Parts 1-2' both become 1.0, the
-- volume filed at the first book it contains.
--
-- Measured against the reference library before writing this: 649 rows carried a sequence, 7 were
-- not plain numbers ('1-3' x3, '1-6', '1-5', '1-2', '1 Parts 1-2'), and all 7 begin with a digit —
-- so that library loses nothing. A library containing 'Prequel' or 'Book Zero' will lose that text,
-- which is the deliberate, accepted cost of the numeric model.
--
-- SQLite cannot ALTER a column's type, so the table is rebuilt. The composite PK, the ordinal
-- column and idx_bsm_series are all recreated to match; FKs are declared here (they are omitted
-- from the .sq file because SQLDelight resolves FK targets only within one file).

-- The old table is renamed ASIDE and the new one created under the real name, rather than the
-- other way round. `ALTER TABLE … RENAME TO` leaves the name QUOTED in sqlite_master, so building
-- the new table as `_new` and renaming it onto the real name yields
-- `CREATE TABLE "book_series_memberships" (…)` — every other table in this schema is unquoted, and
-- the golden-schema test compares that text verbatim. Renaming the doomed copy instead leaves no
-- artifact, because it is dropped.
--
-- The DROP must also precede the CREATE INDEX: idx_bsm_series follows the old table through its
-- rename, so recreating the index before dropping that table collides on the index name.

ALTER TABLE book_series_memberships RENAME TO book_series_memberships_old;

CREATE TABLE book_series_memberships (
    book_id   VARCHAR(36) NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    series_id VARCHAR(36) NOT NULL REFERENCES book_series(id),
    sequence  REAL,
    ordinal   INTEGER NOT NULL,
    PRIMARY KEY (book_id, series_id)
);

INSERT INTO book_series_memberships (book_id, series_id, sequence, ordinal)
SELECT
    book_id,
    series_id,
    CASE
        WHEN sequence IS NULL OR TRIM(sequence) = '' THEN NULL
        WHEN TRIM(sequence) GLOB '[0-9]*' THEN CAST(TRIM(sequence) AS REAL)
        ELSE NULL
    END,
    ordinal
FROM book_series_memberships_old;

DROP TABLE book_series_memberships_old;

CREATE INDEX idx_bsm_series ON book_series_memberships(series_id);
