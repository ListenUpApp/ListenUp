-- Merge receipts: what each series or genre merge changed, recorded in the merge's own
-- transaction so it can be undone. Before this, a series or genre merge was permanent: the merge
-- relinked memberships in place and dropped the links that collided, and nothing remembered which
-- books had come from the merged-away side.
--
-- source_id / target_id reference the rows WITHOUT cascade, deliberately: a merged-away series or
-- genre is only ever tombstoned, and a future tombstone reaper must not delete one an open receipt
-- still needs. merged_by carries no FK — a departed user must not block anything.
CREATE TABLE series_merge_receipts (
    id         VARCHAR(36) NOT NULL PRIMARY KEY,
    source_id  VARCHAR(36) NOT NULL REFERENCES book_series(id),
    target_id  VARCHAR(36) NOT NULL REFERENCES book_series(id),
    merged_at  INTEGER     NOT NULL,
    merged_by  VARCHAR(36) NOT NULL,
    undone_at  INTEGER
);

CREATE INDEX idx_series_merge_receipts_target ON series_merge_receipts(target_id, undone_at);

CREATE TABLE series_merge_receipt_books (
    receipt_id    VARCHAR(36) NOT NULL REFERENCES series_merge_receipts(id) ON DELETE CASCADE,
    book_id       VARCHAR(36) NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    sequence      REAL,
    ordinal       INTEGER     NOT NULL,
    was_in_target INTEGER     NOT NULL,
    PRIMARY KEY (receipt_id, book_id)
);

CREATE TABLE genre_merge_receipts (
    id         VARCHAR(36) NOT NULL PRIMARY KEY,
    source_id  VARCHAR(36) NOT NULL REFERENCES genres(id),
    target_id  VARCHAR(36) NOT NULL REFERENCES genres(id),
    merged_at  INTEGER     NOT NULL,
    merged_by  VARCHAR(36) NOT NULL,
    undone_at  INTEGER
);

CREATE INDEX idx_genre_merge_receipts_target ON genre_merge_receipts(target_id, undone_at);

CREATE TABLE genre_merge_receipt_books (
    receipt_id    VARCHAR(36) NOT NULL REFERENCES genre_merge_receipts(id) ON DELETE CASCADE,
    book_id       VARCHAR(36) NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    was_in_target INTEGER     NOT NULL,
    PRIMARY KEY (receipt_id, book_id)
);

CREATE TABLE genre_merge_receipt_aliases (
    receipt_id VARCHAR(36) NOT NULL REFERENCES genre_merge_receipts(id) ON DELETE CASCADE,
    raw_string TEXT        NOT NULL COLLATE NOCASE,
    PRIMARY KEY (receipt_id, raw_string)
);
