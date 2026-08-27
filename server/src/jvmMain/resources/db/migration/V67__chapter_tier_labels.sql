-- Two-tier chapter grouping.
--
-- The book names its own structure ("Part"/"Book", "Sequence"/"Era"); individual chapters carry
-- the headers that open each group. Both tiers are nullable because most books have neither —
-- a flat chapter list is the common case, and an unnamed tier must stay distinguishable from one
-- named the empty string.
--
-- Headers live on the chapter that OPENS a group rather than as a parent id on every member, so
-- moving a chapter never requires renumbering its siblings and the grouping is derivable by
-- reading the ordered list once.
ALTER TABLE books ADD COLUMN book_tier_label TEXT;
ALTER TABLE books ADD COLUMN part_tier_label TEXT;
ALTER TABLE book_chapters ADD COLUMN part_title TEXT;
ALTER TABLE book_chapters ADD COLUMN book_title TEXT;
