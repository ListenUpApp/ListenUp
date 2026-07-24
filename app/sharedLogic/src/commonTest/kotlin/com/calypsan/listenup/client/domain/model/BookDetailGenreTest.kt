package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.client.TestData
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for [mostSpecificGenre] — the deepest-in-the-hierarchy genre shown
 * as the hero chip beside the Unabridged/Abridged flag on Book Detail.
 */
class BookDetailGenreTest :
    FunSpec({

        fun g(
            name: String,
            path: String,
        ): Genre = TestData.genre(id = path, name = name, slug = name.lowercase(), path = path)

        test("returns the deepest genre by materialized path") {
            val book =
                TestData.bookDetail(
                    genres =
                        listOf(
                            g("Fantasy", "/fiction/fantasy"),
                            g("LitRPG", "/fiction/fantasy/litrpg"),
                        ),
                )

            book.mostSpecificGenre()?.name shouldBe "LitRPG"
        }

        test("returns the first listed genre on a depth tie") {
            val book =
                TestData.bookDetail(
                    genres =
                        listOf(
                            g("Fantasy", "/fantasy"),
                            g("Adventure", "/adventure"),
                        ),
                )

            book.mostSpecificGenre()?.name shouldBe "Fantasy"
        }

        test("returns null when there are no genres") {
            val book = TestData.bookDetail(genres = emptyList())

            book.mostSpecificGenre().shouldBeNull()
        }
    })
