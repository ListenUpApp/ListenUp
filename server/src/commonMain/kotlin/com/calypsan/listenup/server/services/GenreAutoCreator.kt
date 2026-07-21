package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.GenreSyncPayload
import kotlin.uuid.Uuid

/**
 * Find-or-create helper for flat (top-level) live genres.
 *
 * Resolves a raw genre display name to the id of a live genre with the matching
 * canonical slug, creating one if none exists. Creation goes through
 * [GenreRepository.upsert] so the substrate owns the revision bump + sync event —
 * a newly auto-created genre reaches clients exactly like a manually created one.
 *
 * "Flat" means the created genre is a root: `parentId = null`, `depth = 0`,
 * `path = "/<slug>"`. Resolution is case-insensitive because [GenreSlug.normalize]
 * lowercases before slugging ("LitRPG" and "litrpg" share the slug "litrpg").
 *
 * Defensive on blank input: a name that normalizes to an empty slug yields the
 * trimmed name rather than throwing — callers skip blanks, and this guarantees a
 * never-throwing surface for the resolver that drives it.
 */
internal class GenreAutoCreator(
    private val genreRepository: GenreRepository,
) {
    /**
     * Returns the id of the live flat genre for [name], creating it if absent.
     *
     * Steps: normalize [name] to a slug; if a live genre with that slug exists,
     * return its id; otherwise upsert a fresh flat genre and return the new id.
     * On an upsert failure (e.g. a concurrent create winning the slug race), the
     * slug is re-looked-up so the caller still receives a valid id when one now
     * exists. If [name] normalizes to a blank slug, the trimmed name is returned
     * without creating anything.
     */
    suspend fun findOrCreateFlatGenreId(name: String): String {
        val slug =
            when (val normalized = GenreSlug.normalize(name)) {
                is AppResult.Success -> normalized.data
                is AppResult.Failure -> return name.trim()
            }

        genreRepository.findBySlug(slug)?.let { return it.id }

        val newId = Uuid.random().toString()
        val payload =
            GenreSyncPayload(
                id = newId,
                name = name.trim(),
                slug = slug,
                path = "/$slug",
                parentId = null,
                depth = 0,
                sortOrder = 0,
            )

        return when (genreRepository.upsert(payload)) {
            is AppResult.Success -> newId

            // Lost a create race (or transient failure): the row may now exist —
            // re-resolve by slug so the caller still gets a usable id.
            is AppResult.Failure -> genreRepository.findBySlug(slug)?.id ?: newId
        }
    }
}
