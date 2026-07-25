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
}

/**
 * The authoritative disposition of **every** table that carries a `book_id` column, keyed by table
 * name. [BookCascadeRegistryParityTest] asserts this key set equals the set discovered by live schema
 * introspection — in **both** directions — so a new `book_id` table forces an explicit entry here (and,
 * if [RemovalDisposition.CASCADE_TOMBSTONED], a wired soft-delete/revive proven by that same test).
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
        // ── User-owned data that survives removal (never-stranded) ──
        "playback_positions" to RemovalDisposition.USER_DATA,
        "book_reads" to RemovalDisposition.USER_DATA,
        "listening_events" to RemovalDisposition.USER_DATA,
        "activities" to RemovalDisposition.USER_DATA,
        "shelf_books" to RemovalDisposition.USER_DATA,
        "active_sessions" to RemovalDisposition.USER_DATA,
    )
