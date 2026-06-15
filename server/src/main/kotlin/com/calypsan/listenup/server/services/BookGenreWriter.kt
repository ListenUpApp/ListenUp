package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.GenreAliasTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.db.PendingBookGenreTable
import kotlin.time.Clock
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Transaction-scoped helpers for writing the `book_genres` and `pending_book_genres`
 * junction tables.
 *
 * All methods issue Exposed DSL against the already-open transaction or open their own
 * via [suspendTransaction]; they do NOT call
 * [com.calypsan.listenup.server.sync.nextRevision] or publish to the change bus — those
 * are the caller's responsibility. Pair [setBookGenres] with a book `upsert` (which
 * re-reads the junction and emits) so changes propagate.
 */
internal class BookGenreWriter(
    private val db: Database,
    private val clock: Clock,
    private val genreAutoCreator: GenreAutoCreator,
) {
    /**
     * Resolves the scanner's raw genre strings for [bookId] through a 3-step
     * cascade, in precedence order:
     *  1. **Curator alias** — a [GenreAliasTable] row maps the raw string to a
     *     genre id (→ `book_genres`). Custom mappings always win.
     *  2. **Built-in normalization** — [GenreNormalizer] turns the raw string
     *     into canonical slug(s), each resolved against the live taxonomy via
     *     [GenreTable.findBySlug] (→ `book_genres`).
     *  3. **Auto-create** — nothing matched, so the string becomes a flat live
     *     genre via [GenreAutoCreator.findOrCreateFlatGenreId] (→ `book_genres`).
     *     The genre shows to clients immediately rather than waiting in a
     *     curator queue.
     *
     * Idempotent on rescan — wipes the prior `book_genres` and any legacy
     * `pending_book_genres` rows for the book before re-writing, so a book
     * whose `metadata.json` lost a string no longer appears as its source.
     *
     * Inputs are case-insensitive-deduped (`.lowercase().trim()`) so scanning
     * `["Fantasy", "fantasy", "FANTASY"]` yields a single junction row even
     * before the alias's `COLLATE NOCASE` lookup runs. Blank strings are
     * skipped.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    suspend fun processGenreStrings(
        bookId: BookId,
        rawStrings: List<String>,
        now: Long,
    ) {
        val bookIdStr = bookId.value
        BookGenreTable.deleteAllForBook(bookIdStr)
        PendingBookGenreTable.replacePendingForBook(bookIdStr, emptyList(), now)

        for (raw in rawStrings.distinctBy { it.trim().lowercase() }) {
            if (raw.isBlank()) continue
            resolveGenreString(bookIdStr, raw)
        }
    }

    /**
     * Resolves a single raw scanner genre [raw] for [bookIdStr] through the
     * 3-step cascade — curator alias → built-in normalization → auto-create.
     * See [processGenreStrings] for the full contract.
     *
     * Suspend because the auto-create step ([GenreAutoCreator]) goes through the
     * genre substrate's `upsert`. Must be called inside a
     * `suspendTransaction { }` block.
     */
    private suspend fun resolveGenreString(
        bookIdStr: String,
        raw: String,
    ) {
        // 1. Custom curator alias (genre_aliases DB table) wins.
        val customGenreId = GenreAliasTable.resolve(raw)
        if (customGenreId != null) {
            BookGenreTable.insertIfAbsent(bookIdStr, customGenreId)
            return
        }

        // 2. Built-in normalization: raw -> canonical slug(s) -> live taxonomy genre id.
        var resolvedAny = false
        for (slug in GenreNormalizer.normalizeToSlugs(raw)) {
            val genreId = GenreTable.findBySlug(slug) ?: continue
            BookGenreTable.insertIfAbsent(bookIdStr, genreId)
            resolvedAny = true
        }

        // 3. Nothing matched -> auto-create a flat live genre so it shows immediately.
        if (!resolvedAny) {
            val genreId = genreAutoCreator.findOrCreateFlatGenreId(raw)
            BookGenreTable.insertIfAbsent(bookIdStr, genreId)
        }
    }

    /**
     * Replaces [bookId]'s genres with [rawGenres], resolved through the same 3-step cascade as the
     * scanner ([processGenreStrings]: curator alias → [GenreNormalizer] → pending). Idempotent —
     * wipes the book's prior `book_genres`/`pending_book_genres` first. Writes the junction only; it
     * does NOT bump the book's revision or publish — pair it with a book `upsert` (which re-reads the
     * junction and emits) so the change propagates. The match-apply wizard calls this immediately
     * before its text upsert.
     */
    suspend fun setBookGenres(
        bookId: BookId,
        rawGenres: List<String>,
    ): AppResult<Unit> =
        suspendTransaction(db) {
            processGenreStrings(bookId, rawGenres, clock.now().toEpochMilliseconds())
            AppResult.Success(Unit)
        }
}
