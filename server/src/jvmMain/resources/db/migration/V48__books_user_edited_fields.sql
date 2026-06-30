-- Per-field user-edit provenance: hand-edited title/subtitle/description/contributors/series survive
-- rescans (sticky-user-edit merge in BookRepository.writePayload, generalizing the chapter_source guard).
-- Stored as a comma-joined set of uppercase UserEditedField names; '' (the default) means no edits.
ALTER TABLE books ADD COLUMN user_edited_fields TEXT NOT NULL DEFAULT '';
