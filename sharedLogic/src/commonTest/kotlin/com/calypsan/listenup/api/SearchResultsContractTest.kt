package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.BookHit
import com.calypsan.listenup.api.dto.ContributorHit
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.dto.SeriesHit
import com.calypsan.listenup.api.dto.TagHit
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.TagId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Round-trips every search DTO through [contractJson]. Any drift in field names,
 * polymorphic discriminators, or default-value handling fails here before any
 * pipeline code runs.
 */
class SearchResultsContractTest :
    FunSpec({

        test("SearchResults round-trips with all four categories populated") {
            val original =
                SearchResults(
                    books =
                        listOf(
                            BookHit(
                                id = BookId("b1"),
                                title = "The Way of Kings",
                                authorNames = listOf("Brandon Sanderson"),
                                coverPath = "books/b1/cover.jpg",
                                coverHash = "L9Q.AB....",
                            ),
                        ),
                    contributors =
                        listOf(
                            ContributorHit(
                                id = ContributorId("c1"),
                                name = "Brandon Sanderson",
                                sortName = "Sanderson, Brandon",
                                photoPath = null,
                                bookCount = 42,
                            ),
                        ),
                    series =
                        listOf(
                            SeriesHit(
                                id = SeriesId("s1"),
                                name = "The Stormlight Archive",
                                sortName = "Stormlight Archive, The",
                                coverPath = null,
                                bookCount = 5,
                            ),
                        ),
                    tags =
                        listOf(
                            TagHit(
                                id = TagId("tag-1"),
                                slug = "sci-fi",
                                name = "Sci-Fi",
                                bookCount = 12L,
                            ),
                        ),
                )
            roundTrip<SearchResults>(original) shouldBe original
        }

        test("SearchResults round-trips when all four categories are empty") {
            val empty =
                SearchResults(books = emptyList(), contributors = emptyList(), series = emptyList(), tags = emptyList())
            roundTrip<SearchResults>(empty) shouldBe empty
        }

        test("BookHit round-trips with null cover fields") {
            val hit =
                BookHit(
                    id = BookId("b2"),
                    title = "Words of Radiance",
                    authorNames = listOf("Brandon Sanderson"),
                    coverPath = null,
                    coverHash = null,
                )
            roundTrip<BookHit>(hit) shouldBe hit
        }

        test("BookHit round-trips with multiple author names") {
            val hit =
                BookHit(
                    id = BookId("b3"),
                    title = "Good Omens",
                    authorNames = listOf("Terry Pratchett", "Neil Gaiman"),
                    coverPath = "books/b3/cover.jpg",
                    coverHash = null,
                )
            roundTrip<BookHit>(hit) shouldBe hit
        }

        test("ContributorHit round-trips with all optional fields null") {
            val hit =
                ContributorHit(
                    id = ContributorId("c2"),
                    name = "Unknown Narrator",
                    sortName = null,
                    photoPath = null,
                    bookCount = 0,
                )
            roundTrip<ContributorHit>(hit) shouldBe hit
        }

        test("SeriesHit round-trips with cover fields populated") {
            val hit =
                SeriesHit(
                    id = SeriesId("s2"),
                    name = "The Kingkiller Chronicle",
                    sortName = "Kingkiller Chronicle, The",
                    coverPath = "series/s2/cover.jpg",
                    bookCount = 2,
                )
            roundTrip<SeriesHit>(hit) shouldBe hit
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = contractJson.decodeFromString<T>(contractJson.encodeToString(value))
