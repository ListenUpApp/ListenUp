package com.calypsan.listenup.client.presentation.storyworld

import app.cash.turbine.test
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Entity
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.model.WorldEvent
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.client.test.fake.FakeEntityEditRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.client.test.fake.FakeSeriesRepository
import com.calypsan.listenup.client.test.fake.FakeWorldEventEditRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [StoryWorldHubViewModel]. Pins the frontier-gated latest-events/recent-entities
 * projection, the always-3-tile [KindGroup] shape, mention-token rendering (including
 * live-name re-resolution on rename), the unstarted-books banner, and in-hub search.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoryWorldHubViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun entity(
            id: String,
            name: String,
            kind: EntityKind,
            seriesId: String = "series-1",
        ) = Entity(id = id, kind = kind, name = name, homeSeriesId = seriesId)

        fun event(
            id: String,
            text: String,
            mentionIds: List<String> = emptyList(),
            bookId: String? = "book-1",
            positionMs: Long? = null,
            type: WorldEventType = WorldEventType.NOTE,
            seriesId: String = "series-1",
        ) = WorldEvent(
            id = id,
            homeSeriesId = seriesId,
            bookId = bookId,
            positionMs = positionMs,
            type = type,
            text = text,
            mentionIds = mentionIds,
            source = WorldEventSource.MANUAL,
        )

        fun position(
            bookId: String,
            maxPositionMs: Long,
            startedAtMs: Long?,
        ) = PlaybackPosition(
            bookId = bookId,
            positionMs = 0L,
            maxPositionMs = maxPositionMs,
            playbackSpeed = 1.0f,
            hasCustomSpeed = false,
            updatedAtMs = 0L,
            syncedAtMs = null,
            lastPlayedAtMs = null,
            startedAtMs = startedAtMs,
        )

        fun book(
            id: String,
            title: String,
        ) = BookListItem(
            id = BookId(id),
            libraryId = LibraryId("lib-1"),
            folderId = FolderId("folder-1"),
            title = title,
            authors = emptyList(),
            narrators = emptyList(),
            duration = 3_600_000L,
            coverPath = null,
            addedAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
        )

        fun series(
            id: String = "series-1",
            name: String = "The Stormlight Archive",
        ) = Series(id = SeriesId(id), name = name, createdAt = Timestamp(0L))

        /**
         * Fixture wiring every [StoryWorldHubViewModel] dependency as an in-memory fake.
         * [initialPositions] is constructor-supplied (the fake has no post-construction
         * position setter); entities/events/series are mutable post-construction via each
         * fake's own `set*` helper.
         */
        class Fixture(
            initialPositions: Map<String, PlaybackPosition> = emptyMap(),
        ) {
            val entityRepo = FakeEntityEditRepository()
            val eventRepo = FakeWorldEventEditRepository()
            val seriesRepo = FakeSeriesRepository()
            val bookRepo = FakeBookRepository()
            val positionRepo = FakePlaybackPositionRepository(initialPositions)

            fun build() =
                StoryWorldHubViewModel(
                    entityEditRepository = entityRepo,
                    worldEventEditRepository = eventRepo,
                    seriesRepository = seriesRepo,
                    bookRepository = bookRepo,
                    playbackPositionRepository = positionRepo,
                )

            fun seedSeries(books: List<BookListItem> = listOf(book("book-1", "The Way of Kings"))) {
                seriesRepo.setSeriesWithBooks(
                    "series-1",
                    SeriesWithBooks(
                        series = series(),
                        books = books,
                        bookSequences = books.associate { it.id.value to "1" },
                    ),
                )
            }
        }

        test("event beyond the frontier is absent from latestEvents/recentEntities and is counted as hidden") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(
                    listOf(
                        entity("char-1", "Kaladin", EntityKind.CHARACTER),
                        entity("char-2", "Shallan", EntityKind.CHARACTER),
                    ),
                )
                // Recency-sorted DESC input order: the beyond-frontier event is "most recent".
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e-beyond",
                            "[[e:char-2|Shallan]] arrives.",
                            mentionIds = listOf("char-2"),
                            positionMs = 500_000L,
                        ),
                        event(
                            "e-visible",
                            "[[e:char-1|Kaladin]] practices.",
                            mentionIds = listOf("char-1"),
                            positionMs = 50_000L,
                        ),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StoryWorldHubUiState.Idle
                    viewModel.load(WorldRef(seriesId = "series-1"))
                    advanceUntilIdle()
                    awaitItem() shouldBe StoryWorldHubUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StoryWorldHubUiState.Ready>()
                    ready.latestEvents.map { it.id } shouldBe listOf("e-visible")
                    ready.hiddenEventCount shouldBe 1
                    ready.recentEntities.map { it.id } shouldBe listOf("char-1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("showHidden reveals the gated event and hiddenCount drops to 0") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(
                    listOf(
                        entity("char-1", "Kaladin", EntityKind.CHARACTER),
                        entity("char-2", "Shallan", EntityKind.CHARACTER),
                    ),
                )
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e-beyond",
                            "[[e:char-2|Shallan]] arrives.",
                            mentionIds = listOf("char-2"),
                            positionMs = 500_000L,
                        ),
                        event(
                            "e-visible",
                            "[[e:char-1|Kaladin]] practices.",
                            mentionIds = listOf("char-1"),
                            positionMs = 50_000L,
                        ),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StoryWorldHubUiState.Idle
                    viewModel.load(WorldRef(seriesId = "series-1"))
                    advanceUntilIdle()
                    awaitItem() shouldBe StoryWorldHubUiState.Loading
                    awaitItem().shouldBeInstanceOf<StoryWorldHubUiState.Ready>()

                    viewModel.showHidden()
                    advanceUntilIdle()
                    val revealed = awaitItem().shouldBeInstanceOf<StoryWorldHubUiState.Ready>()
                    revealed.hiddenEventCount shouldBe 0
                    revealed.revealed shouldBe true
                    revealed.latestEvents.map { it.id } shouldBe listOf("e-beyond", "e-visible")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("kindGroups always has 3 groups in fixed order, including a zero-count kind") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(
                    listOf(
                        entity("char-1", "Kaladin", EntityKind.CHARACTER),
                        entity("char-2", "Shallan", EntityKind.CHARACTER),
                        entity("loc-1", "Urithiru", EntityKind.LOCATION),
                        // No ITEM entities at all.
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StoryWorldHubUiState.Idle
                    viewModel.load(WorldRef(seriesId = "series-1"))
                    advanceUntilIdle()
                    awaitItem() shouldBe StoryWorldHubUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StoryWorldHubUiState.Ready>()
                    ready.kindGroups.map { it.kind } shouldBe
                        listOf(EntityKind.CHARACTER, EntityKind.LOCATION, EntityKind.ITEM)
                    ready.kindGroups[0].count shouldBe 2
                    ready.kindGroups[0].preview.map { it.name } shouldBe listOf("Kaladin", "Shallan")
                    ready.kindGroups[1].count shouldBe 1
                    ready.kindGroups[2].count shouldBe 0
                    ready.kindGroups[2].preview shouldBe emptyList()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("renderedText resolves mention tokens to the live entity name, not the cached one") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Stormblessed", EntityKind.CHARACTER)))
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e1",
                            "[[e:char-1|Kaladin]] practices.",
                            mentionIds = listOf("char-1"),
                            positionMs = 1_000L,
                        ),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StoryWorldHubUiState.Idle
                    viewModel.load(WorldRef(seriesId = "series-1"))
                    advanceUntilIdle()
                    awaitItem() shouldBe StoryWorldHubUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StoryWorldHubUiState.Ready>()
                    ready.latestEvents.single().renderedText shouldBe "Stormblessed practices."
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("renaming an entity re-renders latestEvents with the new live name") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin", EntityKind.CHARACTER)))
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e1",
                            "[[e:char-1|Kaladin]] practices.",
                            mentionIds = listOf("char-1"),
                            positionMs = 1_000L,
                        ),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StoryWorldHubUiState.Idle
                    viewModel.load(WorldRef(seriesId = "series-1"))
                    advanceUntilIdle()
                    awaitItem() shouldBe StoryWorldHubUiState.Loading
                    val before = awaitItem().shouldBeInstanceOf<StoryWorldHubUiState.Ready>()
                    before.latestEvents.single().renderedText shouldBe "Kaladin practices."

                    fixture.entityRepo.setEntities(listOf(entity("char-1", "Stormblessed", EntityKind.CHARACTER)))
                    advanceUntilIdle()

                    val after = awaitItem().shouldBeInstanceOf<StoryWorldHubUiState.Ready>()
                    after.latestEvents.single().renderedText shouldBe "Stormblessed practices."
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a world with no entities and no events is isEmpty") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.seedSeries()
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StoryWorldHubUiState.Idle
                    viewModel.load(WorldRef(seriesId = "series-1"))
                    advanceUntilIdle()
                    awaitItem() shouldBe StoryWorldHubUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StoryWorldHubUiState.Ready>()
                    ready.isEmpty shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("setSearchQuery filters entities by name, case-insensitively") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(
                    listOf(
                        entity("char-1", "Kaladin", EntityKind.CHARACTER),
                        entity("char-2", "Shallan", EntityKind.CHARACTER),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StoryWorldHubUiState.Idle
                    viewModel.load(WorldRef(seriesId = "series-1"))
                    advanceUntilIdle()
                    awaitItem() shouldBe StoryWorldHubUiState.Loading
                    val initial = awaitItem().shouldBeInstanceOf<StoryWorldHubUiState.Ready>()
                    initial.searchResults shouldBe emptyList()

                    viewModel.setSearchQuery("kal")
                    advanceUntilIdle()
                    val filtered = awaitItem().shouldBeInstanceOf<StoryWorldHubUiState.Ready>()
                    filtered.searchResults.map { it.name } shouldBe listOf("Kaladin")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("unstartedBooksBanner is true when a world book has no position row") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 1_000L, startedAtMs = 1L)),
                        // book-2 has no position row at all.
                    )
                fixture.seedSeries(books = listOf(book("book-1", "Book One"), book("book-2", "Book Two")))
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StoryWorldHubUiState.Idle
                    viewModel.load(WorldRef(seriesId = "series-1"))
                    advanceUntilIdle()
                    awaitItem() shouldBe StoryWorldHubUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StoryWorldHubUiState.Ready>()
                    ready.unstartedBooksBanner shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("unstartedBooksBanner is false once every world book has been started") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf(
                                "book-1" to position("book-1", maxPositionMs = 1_000L, startedAtMs = 1L),
                                "book-2" to position("book-2", maxPositionMs = 500L, startedAtMs = 2L),
                            ),
                    )
                fixture.seedSeries(books = listOf(book("book-1", "Book One"), book("book-2", "Book Two")))
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StoryWorldHubUiState.Idle
                    viewModel.load(WorldRef(seriesId = "series-1"))
                    advanceUntilIdle()
                    awaitItem() shouldBe StoryWorldHubUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StoryWorldHubUiState.Ready>()
                    ready.unstartedBooksBanner shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("world title for a series world is the series name") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.seedSeries()
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StoryWorldHubUiState.Idle
                    viewModel.load(WorldRef(seriesId = "series-1"))
                    advanceUntilIdle()
                    awaitItem() shouldBe StoryWorldHubUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StoryWorldHubUiState.Ready>()
                    ready.worldTitle shouldBe "The Stormlight Archive"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
