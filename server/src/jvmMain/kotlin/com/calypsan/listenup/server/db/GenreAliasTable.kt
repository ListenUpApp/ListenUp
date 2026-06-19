package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Maps raw scanner genre strings to canonical genre ids. Two consumers:
 *  1. **Scanner ingest** — `BookRepository.processGenreStrings` looks up each
 *     raw string via [resolve]. Hits become `book_genres` rows; misses go to
 *     [PendingBookGenreTable] for curator mapping.
 *  2. **Merge cascade** — when a curator merges genre A into B,
 *     [repointAliases] redirects every alias that pointed at A to point at B
 *     so subsequent scans of those raw strings resolve to the surviving genre.
 *
 * The PK column is `COLLATE NOCASE` (V23), so equality lookups are
 * case-insensitive without needing to canonicalize at the helper layer. The
 * helper trims whitespace before lookup/insert to give "  Sci-Fi  " and
 * "Sci-Fi" the same identity.
 *
 * Cascade-deletes follow the genre — if a genre is hard-deleted, its alias
 * rows go with it. Soft-deleted genres are not cascaded (the alias rows
 * remain harmless because `resolve` callers filter genres by `deleted_at`).
 */
internal object GenreAliasTable : Table("genre_aliases") {
    val rawString = varchar("raw_string", 200)
    val genreId = reference("genre_id", GenreTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(rawString)

    init {
        index("idx_genre_aliases_genre", false, genreId)
    }

    /**
     * Returns the genre id for [rawString], or null when no alias matches.
     * Lookup is case-insensitive (V23 `COLLATE NOCASE` on the PK column);
     * input is trimmed so leading/trailing whitespace doesn't produce misses.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun resolve(rawString: String): String? =
        selectAll()
            .where { this@GenreAliasTable.rawString eq rawString.trim() }
            .singleOrNull()
            ?.get(genreId)

    /**
     * Returns every alias string currently pointing at [genreId], in
     * unspecified order. Used by the curator screen to show what raw strings
     * a genre is currently absorbing.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun aliasesForGenre(genreId: String): List<String> =
        selectAll()
            .where { this@GenreAliasTable.genreId eq genreId }
            .map { it[rawString] }

    /**
     * Sets the alias for [rawString] to point at [genreId], overwriting any
     * prior pointer for the same (case-insensitive) raw string. Implemented
     * as delete-then-insert because Exposed v1's SQLite dialect doesn't
     * expose an upsert primitive — and we need the destructive semantics
     * (a curator re-mapping "Sci-Fi" from genre A to genre B should not
     * leave the old pointer in place).
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun addAlias(
        rawString: String,
        genreId: String,
    ) {
        val trimmed = rawString.trim()
        deleteWhere { this@GenreAliasTable.rawString eq trimmed }
        insert {
            it[this@GenreAliasTable.rawString] = trimmed
            it[this@GenreAliasTable.genreId] = genreId
        }
    }

    /**
     * Hard-deletes every alias row referencing [genreId]. Returns the row
     * count. Used by the genre hard-delete cascade and as a pre-step before
     * a curator re-seeds aliases for the genre.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun removeAllForGenre(genreId: String): Int = deleteWhere { this@GenreAliasTable.genreId eq genreId }

    /**
     * Merge cascade: re-points every alias from [fromGenreId] to [toGenreId].
     * Raw SQL because the update touches an FK column (Exposed's typed DSL
     * would force an intermediate `EntityID` wrap and add no clarity here),
     * mirroring the precedent in
     * [BookSeriesMembershipTable.relinkSeries].
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun repointAliases(
        fromGenreId: String,
        toGenreId: String,
    ) {
        TransactionManager.current().exec(
            stmt = "UPDATE genre_aliases SET genre_id = ? WHERE genre_id = ?",
            args =
                listOf(
                    TextColumnType() to toGenreId,
                    TextColumnType() to fromGenreId,
                ),
        )
    }
}
