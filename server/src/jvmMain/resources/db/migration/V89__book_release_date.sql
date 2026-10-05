-- A book's full release date (matching redesign, PR 1; decision 11): ISO yyyy-mm-dd when a catalogue
-- supplied one. Stored, never displayed — every screen shows publish_year. Invariant, enforced in
-- BookRepository: when set, its year equals publish_year; a write that changes the year without a date
-- clears it. Existing rows start NULL.
ALTER TABLE books ADD COLUMN release_date TEXT;
