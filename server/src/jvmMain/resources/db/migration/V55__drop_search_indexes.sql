-- Drops every server-side full-text search index.
--
-- Server-side search is gone. It was a carryover from the Go/Bleve era, when the server ran a
-- genuinely richer engine than the client. After the Kotlin migration both ends ran the same
-- FTS5 algorithm over the same synced corpus, so the server round trip could only ever return
-- what the client already computes locally — minus latency, plus the risk that two indexes with
-- different tokenizers disagree. `SearchService` and `BookService.searchBooks` had zero client
-- consumers; every real search entry point reads the local Room FTS index.
--
-- Four indexes go, in two flavours:
--   * `book_search` — contentless FTS5, written by application code, bridged to books.id
--     through `book_search_map`.
--   * `contributor_search` / `series_search` / `tag_search` — maintained by the AFTER
--     INSERT/UPDATE/DELETE triggers dropped below. The triggers must go first: a trigger whose
--     body references a dropped virtual table fails at write time, not at DROP time, so leaving
--     one behind would break every contributor/series/tag write rather than surfacing here.
--
-- Nothing is lost. All four are derived indexes over rows that remain untouched, and
-- `book_search_map` only ever bridged books.id to the FTS5 INTEGER rowid.
--
-- Consequence for the image: this removes the last FTS5 user on the server. `contentless_delete=1`
-- was the sole reason `Dockerfile.native` compiles a pinned SQLite >= 3.43 rather than using
-- distroless's 3.40, and `-DSQLITE_ENABLE_FTS5` was for these tables. Re-verify what else needs a
-- modern SQLite (JSON1 in particular) before simplifying that builder stage.
DROP TRIGGER IF EXISTS contributors_ad;
DROP TRIGGER IF EXISTS contributors_ai;
DROP TRIGGER IF EXISTS contributors_au;
DROP TRIGGER IF EXISTS series_ad;
DROP TRIGGER IF EXISTS series_ai;
DROP TRIGGER IF EXISTS series_au;
DROP TRIGGER IF EXISTS tags_ad;
DROP TRIGGER IF EXISTS tags_ai;
DROP TRIGGER IF EXISTS tags_au;

DROP TABLE IF EXISTS book_search;
DROP TABLE IF EXISTS book_search_map;
DROP TABLE IF EXISTS contributor_search;
DROP TABLE IF EXISTS series_search;
DROP TABLE IF EXISTS tag_search;
