-- Adds the five rich audio-format fields that the scanner already extracts but the persistence
-- layer was silently dropping. Existing rows are left NULL; a re-scan backfills them.
ALTER TABLE book_audio_files ADD COLUMN codecProfile TEXT;
ALTER TABLE book_audio_files ADD COLUMN spatial TEXT;
ALTER TABLE book_audio_files ADD COLUMN bitrate INTEGER;
ALTER TABLE book_audio_files ADD COLUMN sampleRate INTEGER;
ALTER TABLE book_audio_files ADD COLUMN channels INTEGER;
