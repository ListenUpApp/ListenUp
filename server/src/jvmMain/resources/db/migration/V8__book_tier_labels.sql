-- Chapter tier vocabulary (Chapter Structure/Timing arc, Plan A): the book's own renamable
-- names for its two chapter-grouping tiers — e.g. "Part"/"Book" or "Sequence"/"Era". Nullable;
-- existing rows read back as NULL (tier unnamed), matching the wire payload's forward-compat
-- default. Written ONLY by the targeted BookRepository.setTierLabels update — never touched by
-- the general scalar insert/updateContent path, so a rescan can never clobber a user-set name.
ALTER TABLE books ADD COLUMN book_tier_label TEXT;
ALTER TABLE books ADD COLUMN part_tier_label TEXT;
