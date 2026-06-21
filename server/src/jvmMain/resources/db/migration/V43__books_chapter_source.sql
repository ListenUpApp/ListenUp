-- Chapter provenance: USER-edited chapter sets survive rescans (sticky-chapters guard in writePayload).
ALTER TABLE books ADD COLUMN chapter_source TEXT NOT NULL DEFAULT 'embedded';
