-- Per-book scan-warning flag: true when the book ingested with an unreadable audio file.
ALTER TABLE books ADD COLUMN has_scan_warning INTEGER NOT NULL DEFAULT 0;
