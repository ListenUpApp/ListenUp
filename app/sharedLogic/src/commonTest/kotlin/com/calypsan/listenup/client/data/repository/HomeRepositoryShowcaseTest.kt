package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.domain.model.ShelfCover
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/** The new-listener slot's facts (spec §2.3): the newest books that have art, and never a count of the library. */
class HomeRepositoryShowcaseTest :
    FunSpec({
        fun recent(
            id: String,
            coverHash: String?,
        ) = DiscoveryBook(
            id = id,
            title = "Book $id",
            authorName = null,
            coverPath = coverHash?.let { "/covers/$id.jpg" },
            coverHash = coverHash,
            createdAt = 0L,
        )

        test("the showcase takes the newest four books that have a cover, newest first") {
            runTest {
                val books =
                    mock<BookRepository> {
                        every { observeRecentlyAddedBooks(any()) } returns
                            flowOf(
                                listOf(
                                    recent("n1", "h1"),
                                    recent("n2", null),
                                    recent("n3", "h3"),
                                    recent("n4", "h4"),
                                    recent("n5", "h5"),
                                    recent("n6", "h6"),
                                ),
                            )
                    }
                val repo = homeRepositoryOf(books, mock<PlaybackPositionDao>())

                val showcase = repo.observeLibraryShowcase(coverLimit = 4).first()

                showcase.covers shouldBe
                    listOf(
                        ShelfCover("n1", "/covers/n1.jpg", "h1"),
                        ShelfCover("n3", "/covers/n3.jpg", "h3"),
                        ShelfCover("n4", "/covers/n4.jpg", "h4"),
                        ShelfCover("n5", "/covers/n5.jpg", "h5"),
                    )
            }
        }

        test("a library whose newest books have no art shows no covers rather than blanks") {
            runTest {
                val books =
                    mock<BookRepository> {
                        every { observeRecentlyAddedBooks(any()) } returns flowOf(listOf(recent("n1", null)))
                    }
                val repo = homeRepositoryOf(books, mock<PlaybackPositionDao>())

                repo.observeLibraryShowcase(coverLimit = 4).first().covers shouldBe emptyList()
            }
        }
    })
