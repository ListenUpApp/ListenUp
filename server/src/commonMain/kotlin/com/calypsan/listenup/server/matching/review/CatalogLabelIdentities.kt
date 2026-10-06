package com.calypsan.listenup.server.matching.review

import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.GenreNormalizer
import com.calypsan.listenup.server.sync.MoodSlug

/**
 * A suggested genre is one you have when it names it (case and spacing aside), or when the scanner's cascade —
 * curator alias, then the built-in normaliser — resolves it only to genres you already have. Nothing is created.
 */
internal class GenreLabelIdentity(
    private val db: ListenUpDatabase,
    private val yourGenreIds: Set<String>,
) : LabelIdentity {
    override suspend fun same(
        suggested: String,
        yours: List<String>,
    ): Boolean {
        if (yours.any { ReviewKeys.text(it) == ReviewKeys.text(suggested) }) return true
        val resolved =
            suspendTransaction(db) {
                db.genreAliasesQueries.resolve(suggested.trim()).executeAsOneOrNull()?.let { listOf(it) }
                    ?: GenreNormalizer
                        .normalizeToSlugs(suggested)
                        .mapNotNull { slug -> db.genresQueries.findBySlug(slug).executeAsOneOrNull() }
            }
        return resolved.isNotEmpty() && yourGenreIds.containsAll(resolved)
    }
}

/** A suggested mood is one you have when both names normalise to the same mood slug. */
internal object MoodLabelIdentity : LabelIdentity {
    override suspend fun same(
        suggested: String,
        yours: List<String>,
    ): Boolean {
        val slug = MoodSlug.normalize(suggested).getOrElse { return false }
        return yours.any { MoodSlug.normalize(it).getOrElse { null } == slug }
    }
}
