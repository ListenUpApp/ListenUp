-- Series form a tree (#962): Cosmere > Mistborn > Era 1. parent_id is the single parent (NULL for
-- a root); parent_position orders a series among its siblings. Like merged_into (V69) the link
-- carries no FK: a live series whose parent is gone is simply read as a root.
ALTER TABLE book_series ADD COLUMN parent_id VARCHAR(36);
ALTER TABLE book_series ADD COLUMN parent_position INTEGER;
CREATE INDEX idx_book_series_parent ON book_series(parent_id);

-- What a merge did to the hierarchy: the sub-series the merged-away series held, with the
-- position each had there, so undoing the merge can hand them back. child_id carries no FK, for
-- the same reason parent_id does not: a series is only ever tombstoned, and the undo reads live
-- rows only.
CREATE TABLE series_merge_receipt_children (
    receipt_id VARCHAR(36) NOT NULL REFERENCES series_merge_receipts(id) ON DELETE CASCADE,
    child_id   VARCHAR(36) NOT NULL,
    position   INTEGER,
    PRIMARY KEY (receipt_id, child_id)
);
