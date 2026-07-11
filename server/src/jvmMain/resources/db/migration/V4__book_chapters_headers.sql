-- Nested chapters: optional Book/Part header labels on the chapter that opens each section.
-- Nullable; existing rows read back as NULL (flat books, unchanged rendering).
ALTER TABLE book_chapters ADD COLUMN part_title TEXT;
ALTER TABLE book_chapters ADD COLUMN book_title TEXT;
