package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase

/**
 * Helpers for writing the `book_genres` and `pending_book_genres` junction tables over
 * SQLDelight.
 *
 * The methods are **suspend** because the auto-create branch goes through
 * [GenreAutoCreator] → [GenreRepository.upsert], which opens its own SQLDelight transaction
 * (and bumps revision + publishes). They therefore cannot run inside a single enclosing
 * SQLDelight transaction (its body is non-suspend) — each synchronous junction query
 * auto-commits, and the suspend auto-create upsert commits its own transaction. This matches
 * the contention-free sequential-write model the genre cutover adopts: sequential SQLDelight
 * writes never take the SQLite write lock concurrently, so there is no `SQLITE_BUSY`.
 *
 * These writers do NOT bump the book's revision or publish a `book.Updated` themselves — that
 * is the caller's responsibility. Pair a junction write with a book `upsert` (which re-reads
 * the junction and emits) so the change propagates.
 */
internal class BookGenreWriter(
    private val db: ListenUpDatabase,
    private val clock: kotlin.time.Clock,
    private val genreAutoCreator: GenreAutoCreator,
) {
    /**
     * Resolves the scanner's raw genre strings for [bookId] through a 3-step cascade,
     * in precedence order:
     *  1. **Curator alias** — a `genre_aliases` row maps the raw string to a genre id
     *     (→ `book_genres`). Custom mappings always win.
     *  2. **Built-in normalization** — [GenreNormalizer] turns the raw string into
     *     canonical slug(s), each resolved against the live taxonomy via
     *     `genresQueries.findBySlug` (→ `book_genres`).
     *  3. **Auto-create** — nothing matched, so the string becomes a flat live genre via
     *     [GenreAutoCreator.findOrCreateFlatGenreId] (→ `book_genres`). The genre shows
     *     to clients immediately rather than waiting in a curator queue.
     *
     * Idempotent on rescan — wipes the prior `book_genres` and any legacy
     * `pending_book_genres` rows for the book before re-writing, so a book whose
     * `metadata.json` lost a string no longer appears as its source.
     *
     * Inputs are case-insensitive-deduped (`.lowercase().trim()`) so scanning
     * `["Fantasy", "fantasy", "FANTASY"]` yields a single junction row even before the
     * alias's `COLLATE NOCASE` lookup runs. Blank strings are skipped.
     *
     * [now] is retained for signature compatibility with the scanner-ingest caller; the
     * pending-row wipe needs no timestamp (it only deletes).
     */
    suspend fun processGenreStrings(
        bookId: BookId,
        rawStrings: List<String>,
        @Suppress("UNUSED_PARAMETER") now: Long,
    ) {
        val bookIdStr = bookId.value
        db.bookGenresQueries.deleteByBookId(bookIdStr)
        db.pendingBookGenresQueries.deleteAllForBook(bookIdStr)

        for (raw in rawStrings.distinctBy { it.trim().lowercase() }) {
            if (raw.isBlank()) continue
            resolveGenreString(bookIdStr, raw)
        }
    }

    /**
     * Resolves a single raw scanner genre [raw] for [bookId] through the 3-step cascade —
     * curator alias → built-in normalization → auto-create — and links the result, WITHOUT
     * wiping the book's existing `book_genres`. Unlike [processGenreStrings] this is purely
     * additive: it only ever adds links via `insertIfAbsent`, so a book's already-live genres
     * survive.
     *
     * This is the entry point for the one-time legacy `pending_book_genres` backlog promotion
     * ([PendingGenrePromotion]), which must light up unresolved strings without disturbing
     * genres a book already has.
     */
    suspend fun resolveAndLink(
        bookId: BookId,
        raw: String,
    ) {
        if (raw.isBlank()) return
        resolveGenreString(bookId.value, raw)
    }

    /**
     * Resolves a single raw scanner genre [raw] for [bookIdStr] through the 3-step cascade —
     * curator alias → built-in normalization → auto-create — and links each resolved id. See
     * [processGenreStrings] for the full contract. Suspend because the auto-create step
     * ([GenreAutoCreator]) goes through the genre substrate's `upsert`.
     */
    private suspend fun resolveGenreString(
        bookIdStr: String,
        raw: String,
    ) {
        for (genreId in resolveGenreIds(raw)) {
            db.bookGenresQueries.insertIfAbsent(bookIdStr, genreId)
        }
    }

    /**
     * Resolves a single raw scanner genre [raw] to the list of genre ids it links to, through the
     * 3-step cascade — curator alias → built-in normalization → auto-create — WITHOUT writing any
     * junction row. Exposed so the batched scan-persist path can pre-resolve every distinct raw
     * string ONCE (auto-creating new genres up front) in the suspend prepare phase, then write the
     * `book_genres` junctions synchronously inside the chunk transaction via [writeJunctions].
     *
     * The cascade's precedence and auto-create-on-miss behaviour is identical to the per-book
     * [processGenreStrings] path; only the junction write is deferred. A blank string resolves to
     * an empty list (the caller skips it).
     */
    suspend fun resolveGenreIds(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()

        // 1. Custom curator alias (genre_aliases) wins. Lookup is case-insensitive
        //    (COLLATE NOCASE on the PK); trim to match the Exposed helper semantics.
        val customGenreId = db.genreAliasesQueries.resolve(raw.trim()).executeAsOneOrNull()
        if (customGenreId != null) {
            return listOf(customGenreId)
        }

        // 2. Built-in normalization: raw -> canonical slug(s) -> live taxonomy genre id.
        val normalized =
            GenreNormalizer
                .normalizeToSlugs(raw)
                .mapNotNull { slug -> db.genresQueries.findBySlug(slug).executeAsOneOrNull() }
        if (normalized.isNotEmpty()) {
            return normalized
        }

        // 3. Nothing matched -> auto-create a flat live genre so it shows immediately.
        return listOf(genreAutoCreator.findOrCreateFlatGenreId(raw))
    }

    /**
     * Synchronously writes [bookIdStr]'s genre junction rows for the pre-resolved [genreIds],
     * inside the caller's open SQLDelight transaction — the batched-scan counterpart to
     * [processGenreStrings]'s write half. Idempotent on rescan: wipes the prior `book_genres` and
     * any legacy `pending_book_genres` rows for the book first, then `insertIfAbsent` per id (so a
     * book whose `metadata.json` lost a string no longer appears as its source). No suspend, no
     * auto-create — every id must already be resolved by [resolveGenreIds].
     */
    fun writeJunctions(
        bookIdStr: String,
        genreIds: List<String>,
    ) {
        db.bookGenresQueries.deleteByBookId(bookIdStr)
        db.pendingBookGenresQueries.deleteAllForBook(bookIdStr)
        for (genreId in genreIds.distinct()) {
            db.bookGenresQueries.insertIfAbsent(bookIdStr, genreId)
        }
    }

    /**
     * Replaces [bookId]'s genres with [rawGenres], resolved through the same 3-step cascade as the
     * scanner ([processGenreStrings]: curator alias → [GenreNormalizer] → auto-create). Idempotent —
     * wipes the book's prior `book_genres`/`pending_book_genres` first. Writes the junction only; it
     * does NOT bump the book's revision or publish — pair it with a book `upsert` (which re-reads the
     * junction and emits) so the change propagates. The match-apply wizard calls this immediately
     * before its text upsert.
     */
    suspend fun setBookGenres(
        bookId: BookId,
        rawGenres: List<String>,
    ): AppResult<Unit> {
        processGenreStrings(bookId, rawGenres, clock.now().toEpochMilliseconds())
        return AppResult.Success(Unit)
    }
}
