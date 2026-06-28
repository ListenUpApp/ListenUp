package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/**
 * Materializes an Audible category ladder (root → leaf) into a nested genre
 * hierarchy, returning the rung ids in the same order (last = most specific).
 *
 * Audible returns each book's categories as ordered ladders — e.g.
 * `Fiction → Fantasy → LitRPG`. The flat scanner path collapses these into
 * unrelated top-level genres; this class preserves the parentage so browsing
 * "Fiction" surfaces every book under it and the leaf rung is the precise genre.
 *
 * **Don't-clobber-manual-curation rule:** a rung is reparented under the
 * previous rung only when it is still *unarranged* — `parentId == null` and
 * `depth == 0`. A genre that already has a parent (placed there by a curator or
 * an earlier ladder) keeps its arrangement; its `parentId`/`path`/`depth` are
 * never rewritten. Flat genres — whether just auto-created or left over from a
 * prior scan — ARE nested, so an Audible match seeds the hierarchy onto genres
 * the scanner created flat. (This matters: the match-apply flow first writes the
 * flattened ladder rungs as flat genres, then runs this to nest them.)
 *
 * Reuses [GenreAutoCreator] for find-or-create and the same materialized-path
 * primitives (the SQLDelight `genresQueries.rewritePathPrefix` / `updateParentId`)
 * that `GenreServiceImpl.moveGenre` uses to reparent. Each reparented rung is
 * re-upserted through [GenreRepository] so the substrate bumps the revision and
 * publishes a `genre.Updated` event — clients see the new nesting immediately.
 */
internal class GenreHierarchyFromLadder(
    private val sqlDb: ListenUpDatabase,
    private val genreRepository: GenreRepository,
    private val genreAutoCreator: GenreAutoCreator,
) {
    /**
     * Ensures every rung in [names] (root → leaf) exists and is nested under its
     * predecessor, returning the rung ids in order (last = most specific genre).
     *
     * For each name: resolve-or-create a flat genre; if a previous rung exists and
     * this rung is still unarranged (`parentId == null && depth == 0`), reparent
     * it under that rung. A rung that already has a parent keeps its arrangement.
     * Blank names that resolve to no genre are skipped.
     */
    suspend fun ensureLadder(names: List<String>): List<String> {
        val rungIds = mutableListOf<String>()
        var previousId: String? = null

        for (name in names) {
            if (name.isBlank()) continue

            val id = genreAutoCreator.findOrCreateFlatGenreId(name)

            val prev = previousId
            if (prev != null) {
                val child = genreRepository.findById(id)
                // Nest only unarranged (flat) rungs — fresh or scanner-created. A rung the
                // curator/an earlier ladder already gave a parent is left untouched.
                if (child != null && child.parentId == null && child.depth == 0) {
                    reparentUnder(childId = id, parentId = prev)
                }
            }

            rungIds += id
            previousId = id
        }

        return rungIds
    }

    /**
     * Reparents the freshly-created flat genre [childId] under [parentId],
     * rewriting its materialized path and depth, then re-upserts it so the
     * substrate emits the change. The child is always a just-created leaf with no
     * descendants, so the single-row path rewrite suffices. Mirrors
     * `GenreServiceImpl.executeMove` (same SQLDelight queries, same `substr_from`
     * = `oldPrefix.length + 1` convention).
     */
    private suspend fun reparentUnder(
        childId: String,
        parentId: String,
    ) {
        val parent = genreRepository.findById(parentId) ?: return
        val child = genreRepository.findById(childId) ?: return

        val newPath = "${parent.path}/${child.slug}"
        if (newPath == child.path) return
        val depthDelta = parent.depth + 1 - child.depth

        suspendTransaction(sqlDb) {
            sqlDb.genresQueries.rewritePathPrefix(
                new_prefix = newPath,
                // substr_from = oldPrefix.length + 1; CAST to INTEGER at eval time (see Genres.sq).
                substr_from = (child.path.length + 1).toString(),
                depth_delta = depthDelta.toLong(),
                old_prefix = child.path,
            )
            sqlDb.genresQueries.updateParentId(parent_id = parentId, id = childId)
        }

        genreRepository.findById(childId)?.let { genreRepository.upsert(it) }
    }
}
