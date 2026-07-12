-- sidecar_write_state: the round-trip discriminator for the Foundation Trio Phase 2
-- listenup.json sidecar writer (see server/.../sidecar/SidecarWriter.kt). One row per
-- book that has ever had a listenup.json written by this server, holding the SHA-256
-- content hash of the bytes it last wrote. On rescan the reader compares this hash
-- against the on-disk file's hash: a match means the file is exactly what we wrote
-- (SelfWritten — skip re-ingestion); a mismatch means a human or another tool edited
-- it since (External — ingest it).
CREATE TABLE sidecar_write_state (
    book_id      VARCHAR(36) NOT NULL PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE,
    content_hash TEXT NOT NULL,
    written_at   BIGINT NOT NULL
);
