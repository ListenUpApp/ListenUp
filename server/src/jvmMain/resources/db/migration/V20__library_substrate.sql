-- DESTRUCTIVE: nuke books and all child rows; scanner re-populates per
-- no-existing-users policy. Clears FK references before dropping libraries.
DELETE FROM book_search_map;
DELETE FROM book_audio_files;
DELETE FROM book_chapters;
DELETE FROM book_contributors;
DELETE FROM book_series_memberships;
DELETE FROM books;

-- DESTRUCTIVE: libraries table existed as a single-bootstrap-row from Books-A;
-- nuke-and-pave under the no-existing-users policy. New schema adds syncable
-- substrate columns, drops the single root_path column, and adds multi-user
-- forward-staging columns (access_mode, created_by_user_id).
DROP TABLE IF EXISTS libraries;

CREATE TABLE libraries (
    id                  VARCHAR(36)  NOT NULL PRIMARY KEY,
    name                VARCHAR(256) NOT NULL,
    metadata_precedence VARCHAR(256) NOT NULL DEFAULT 'embedded,abs,sidecar',
    access_mode         VARCHAR(16)  NOT NULL DEFAULT 'shared',
    created_by_user_id  VARCHAR(36),
    created_at          INTEGER      NOT NULL,
    revision            INTEGER      NOT NULL DEFAULT 0,
    updated_at          INTEGER      NOT NULL DEFAULT 0,
    deleted_at          INTEGER,
    client_op_id        TEXT
);
CREATE INDEX idx_libraries_revision ON libraries(revision);

CREATE TABLE library_folders (
    id          VARCHAR(36)   NOT NULL PRIMARY KEY,
    library_id  VARCHAR(36)   NOT NULL REFERENCES libraries(id) ON DELETE CASCADE,
    root_path   VARCHAR(1024) NOT NULL,
    created_at  INTEGER       NOT NULL,
    revision    INTEGER       NOT NULL DEFAULT 0,
    updated_at  INTEGER       NOT NULL DEFAULT 0,
    deleted_at  INTEGER,
    client_op_id TEXT
);
CREATE UNIQUE INDEX idx_library_folders_root_path ON library_folders(root_path) WHERE deleted_at IS NULL;
CREATE INDEX idx_library_folders_library_id ON library_folders(library_id);
CREATE INDEX idx_library_folders_revision ON library_folders(revision);

-- Add folder_id to books; library_id already exists from V4.
-- Stored without an explicit FK reference in SQLite for compatibility with
-- ALTER TABLE (SQLite does not support ADD COLUMN REFERENCES + NOT NULL + DEFAULT).
-- Application layer enforces the foreign-key relationship at the service boundary.
ALTER TABLE books ADD COLUMN folder_id VARCHAR(36) DEFAULT 'PENDING-LIB-C';
CREATE INDEX idx_books_library_id ON books(library_id);
CREATE INDEX idx_books_folder_id ON books(folder_id);
