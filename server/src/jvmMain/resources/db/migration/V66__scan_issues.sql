-- A folder the scanner walked but could not import.
--
-- Until now this was a `logger.warn` line and nothing else: the book simply was not in the
-- library, with no trace anywhere the user could reach. That is the failure mode the app exists
-- to not have — silence about something that went wrong. One row per broken folder, held until
-- the folder scans cleanly or an admin dismisses it.
--
-- `root_rel_path` is relative to the library folder root so it reads like the user's own tree.
-- The UNIQUE pair is what makes a repeated scan update an issue rather than pile up duplicates:
-- the same broken folder is one problem, however many times it is walked.
CREATE TABLE scan_issues (
    id              TEXT    NOT NULL PRIMARY KEY,
    library_id      TEXT    NOT NULL,
    root_rel_path   TEXT    NOT NULL,
    reason          TEXT    NOT NULL,
    detail          TEXT,
    first_seen_at   INTEGER NOT NULL,
    last_seen_at    INTEGER NOT NULL,
    dismissed_at    INTEGER
);

CREATE UNIQUE INDEX idx_scan_issue_path ON scan_issues(library_id, root_rel_path);

-- Open issues, oldest first — the only read the inbox performs.
CREATE INDEX idx_scan_issue_open ON scan_issues(library_id, dismissed_at, first_seen_at);
