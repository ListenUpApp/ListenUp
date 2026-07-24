package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.client.domain.model.Genre
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for genreMoveCandidates — the cycle-safe target list for the
 * manual "Move to…" reparent fallback.
 */
class GenreMoveCandidatesTest :
    FunSpec({

        fun g(
            id: String,
            path: String,
        ): Genre = Genre(id = id, name = id, slug = id, path = path)

        val fiction = g("fiction", "/fiction")
        val fantasy = g("fantasy", "/fiction/fantasy")
        val epic = g("epic", "/fiction/fantasy/epic")
        val nonfic = g("nonfic", "/non-fiction")
        val all = listOf(fiction, fantasy, epic, nonfic)

        test("excludes the source itself and its descendants") {
            // Moving 'fantasy' excludes 'fantasy' and 'epic' (epic's path
            // '/fiction/fantasy/epic' starts with '/fiction/fantasy/').
            genreMoveCandidates(all, fantasy).map { it.id } shouldBe listOf("fiction", "nonfic")
        }

        test("excludes the source's descendants to prevent cycles") {
            // Moving 'fiction' cannot target 'fantasy' or 'epic' (its subtree).
            genreMoveCandidates(all, fiction).map { it.id } shouldBe listOf("nonfic")
        }

        test("prefix match does not over-exclude sibling-prefixed paths") {
            val fic = g("fic", "/fic")
            val ficClassics = g("ficc", "/fic-classics")
            genreMoveCandidates(listOf(fic, ficClassics), fic).map { it.id } shouldBe listOf("ficc")
        }

        test("leaf genre returns all genres except itself") {
            genreMoveCandidates(all, nonfic).map { it.id } shouldBe
                listOf("fiction", "fantasy", "epic")
        }
    })
