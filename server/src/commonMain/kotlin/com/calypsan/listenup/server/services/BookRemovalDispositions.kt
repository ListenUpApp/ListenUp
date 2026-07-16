package com.calypsan.listenup.server.services

/**
 * How a `book_id`-owning table behaves when its parent book is **soft-removed** ([BookRepository.softDelete]).
 *
 * This registry is the declared companion to the removal cascade. `BookRepositorySoftDeleteCascadeTest`
 * proves the *behaviour*; [BookCascadeRegistryParityTest] proves this **map stays complete** — schema
 * introspection fails the build the moment a new `book_id` table is added without a considered
 * disposition here. That parity is the guard that would have caught the moods/collection_books cascade
 * gap directly: a new table can no longer silently escape the removal decision.
 */
internal enum class RemovalDisposition {
    /**
     * A user-facing membership junction whose live rows are **tombstoned** by
     * [BookRepository.softDelete] and **revived** by [BookRepository.reviveByIds] (floored on the
     * removal instant), so clients receive per-row tombstones and a remove-then-rescan restores the
     * membership. The dead book must leave these — visibility, counts, and the access union all key
     * off live rows here. Verified behaviourally per table by [BookCascadeRegistryParityTest].
     */
    CASCADE_TOMBSTONED,

    /**
     * A child owned by the book aggregate (FK `ON DELETE CASCADE`), rewritten wholesale on every
     * rescan. It is **not** an independent soft-delete cascade target: under a tombstoned parent its
     * rows are unreachable (the parent hides them) and they are rebuilt on re-ingest. Adding one of
     * these is a book-aggregate change, not an access change.
     */
    HARD_CHILD,

    /**
     * User-owned data keyed by `book_id` that **deliberately survives** a book removal — playback
     * positions, read history, listening events, activity, shelf placement, active sessions. It is
     * not a cascade target: a re-added book keeps the user's position and history (the never-stranded
     * contract), and a dead book never surfaces because every read path gates on live books.
     */
    USER_DATA,

    /**
     * The FTS5 search-index shadow keyed by `book_id`. Maintained by the search-index write path
     * ([com.calypsan.listenup.server.services.BookFtsWriter]) — a rowid↔book_id map, not a removal
     * cascade target. A tombstoned book is filtered from search results by the book-liveness join +
     * access gate, so its stale map row is inert and is overwritten (rowid reused) on re-ingest.
     */
    SEARCH_SHADOW,
}

/**
 * The authoritative disposition of **every** table that carries a `book_id` column, keyed by table
 * name. [BookCascadeRegistryParityTest] asserts this key set equals the set discovered by live schema
 * introspection — in **both** directions — so a new `book_id` table forces an explicit entry here (and,
 * if [RemovalDisposition.CASCADE_TOMBSTONED], a wired soft-delete/revive proven by that same test).
 *
 * Note on the search tables: the FTS5 virtual table `book_search` tracks books by `rowid`, not a
 * `book_id` column (recreated contentless in V21), so it is not a `book_id` table. Its persistent
 * rowid↔book_id map `book_search_map` (V9) **does** carry `book_id` and is the [RemovalDisposition
 * .SEARCH_SHADOW] entry below.
 */
internal val bookIdTableDispositions: Map<String, RemovalDisposition> =
    mapOf(
        // ── Membership junctions the removal cascade tombstones + revives ──
        "book_tags" to RemovalDisposition.CASCADE_TOMBSTONED,
        "book_moods" to RemovalDisposition.CASCADE_TOMBSTONED,
        "collection_books" to RemovalDisposition.CASCADE_TOMBSTONED,
        // ── Book-aggregate children, rewritten on rescan (FK ON DELETE CASCADE) ──
        "book_audio_files" to RemovalDisposition.HARD_CHILD,
        "book_chapters" to RemovalDisposition.HARD_CHILD,
        "book_contributors" to RemovalDisposition.HARD_CHILD,
        "book_series_memberships" to RemovalDisposition.HARD_CHILD,
        "book_genres" to RemovalDisposition.HARD_CHILD,
        "book_documents" to RemovalDisposition.HARD_CHILD,
        "pending_book_genres" to RemovalDisposition.HARD_CHILD,
        // Sidecar write-state: server-internal round-trip bookkeeping (FK ON DELETE CASCADE),
        // rewritten by the SidecarWriter on every curation flush. Under a tombstoned parent the
        // row is inert (the reader only consults it for hash-skip, and a matching hash on a
        // still-on-disk file is still the truth); a hard delete removes it via the FK.
        "sidecar_write_state" to RemovalDisposition.HARD_CHILD,
        // ── User-owned data that survives removal (never-stranded) ──
        "playback_positions" to RemovalDisposition.USER_DATA,
        "book_reads" to RemovalDisposition.USER_DATA,
        "listening_events" to RemovalDisposition.USER_DATA,
        "activities" to RemovalDisposition.USER_DATA,
        "shelf_books" to RemovalDisposition.USER_DATA,
        "reading_order_books" to RemovalDisposition.USER_DATA,
        "active_sessions" to RemovalDisposition.USER_DATA,
        // world_events: Story World log rows optionally anchored to a book position via book_id.
        // The anchor is curated content, not book-owned FK membership — a removed book's anchored
        // events simply become unreachable via any book-gated read path (same as the events'
        // dual home) until the book is re-added, at which point the anchor resolves again
        // (never-stranded: removal never destroys a caller's story-world notes).
        "world_events" to RemovalDisposition.USER_DATA,
        // ── FTS5 search-index shadow (rowid↔book_id map) ──
        "book_search_map" to RemovalDisposition.SEARCH_SHADOW,
    )
