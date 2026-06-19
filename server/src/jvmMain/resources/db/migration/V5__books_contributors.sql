CREATE TABLE contributors (
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    normalized_name VARCHAR(512) NOT NULL,
    name            VARCHAR(512) NOT NULL,
    sort_name       VARCHAR(512)
);
CREATE UNIQUE INDEX idx_contributor_normalized ON contributors(normalized_name);

CREATE TABLE book_contributors (
    book_id        VARCHAR(36) NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    contributor_id VARCHAR(36) NOT NULL REFERENCES contributors(id),
    role           VARCHAR(64) NOT NULL,
    credited_as    VARCHAR(512),
    ordinal        INTEGER NOT NULL,
    PRIMARY KEY (book_id, contributor_id, role)
);
CREATE INDEX idx_bc_contributor_role ON book_contributors(contributor_id, role);
