package com.calypsan.listenup.client.data.local.db

/*
 * Data classes for FTS5 full-text search entries.
 *
 * These are NOT Room entities — the three `*_fts` virtual tables are created
 * manually by `FtsTableCallback` in the platform DatabaseModules and read via
 * raw SQL in SearchDao.
 *
 * Why not @Fts5 @Entity? Room 2.8.4 only ships `@Fts3` and `@Fts4`; the `@Fts5`
 * annotation is Room 3.0.0-alpha only (not production-ready). Falling back to
 * @Fts4 would force us onto fts4 virtual tables, losing `bm25()` relevance
 * ranking and the FTS5 tokenizer pipeline we currently depend on in SearchDao.
 *
 * Revisit when Room 3.x ships a stable release — at that point these data
 * classes fold into @Fts5 @Entity declarations and the Callback disappears
 * (restoration-roadmap W4.4 deferred item).
 *
 * Why standalone tables (not external content)?
 * - Simpler sync: delete all + reinsert during sync
 * - No trigger complexity
 * - Small storage overhead is acceptable for reliability
 */

/**
 * FTS5 entry for book full-text search.
 *
 * Denormalizes author/narrator names from the cross-reference table
 * into the FTS index for efficient search.
 */
data class BookFtsEntry(
    val bookId: String,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val author: String?,
    val narrator: String?,
    val seriesName: String?,
    val genres: String?,
)

/**
 * FTS5 entry for contributor full-text search.
 */
data class ContributorFtsEntry(
    val contributorId: String,
    val name: String,
    val description: String?,
)

/**
 * FTS5 entry for series full-text search.
 */
data class SeriesFtsEntry(
    val seriesId: String,
    val name: String,
    val description: String?,
)
