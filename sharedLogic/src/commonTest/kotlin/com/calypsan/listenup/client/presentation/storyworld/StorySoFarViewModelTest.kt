package com.calypsan.listenup.client.presentation.storyworld

import app.cash.turbine.test
import com.calypsan.listenup.api.core.MentionTokens
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Entity
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.ReadingOrder
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.model.WorldEvent
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.client.test.fake.FakeEntityEditRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackManager
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.client.test.fake.FakeReadingOrderRepository
import com.calypsan.listenup.client.test.fake.FakeSeriesRepository
import com.calypsan.listenup.client.test.fake.FakeWorldEventEditRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
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
 * Tests for [StorySoFarViewModel]. Pins the [FoldClock] selection (ordered-order-scoped vs.
 * per-book, and the no-order floor for a multi-book series with nothing followed), the
 * empty-floor/asOfHere widening behavior, per-row location/status/recency projection, and
 * CHARACTER-first row grouping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorySoFarViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun entity(
            id: String,
            name: String,
            kind: EntityKind,
            seriesId: String? = "series-1",
            bookId: String? = null,
        ) = Entity(id = id, kind = kind, name = name, homeSeriesId = seriesId, homeBookId = bookId)

        fun event(
            id: String,
            text: String,
            mentionIds: List<String> = emptyList(),
            bookId: String? = "book-1",
            positionMs: Long? = null,
            type: WorldEventType = WorldEventType.NOTE,
            seriesId: String? = "series-1",
            homeBookId: String? = null,
            subjectEntityId: String? = null,
            objectEntityId: String? = null,
        ) = WorldEvent(
            id = id,
            homeSeriesId = seriesId,
            homeBookId = homeBookId,
            bookId = bookId,
            positionMs = positionMs,
            type = type,
            text = text,
            subjectEntityId = subjectEntityId,
            objectEntityId = objectEntityId,
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

        fun readingOrder(
            id: String,
            name: String,
        ) = ReadingOrder(
            id = ReadingOrderId(id),
            name = name,
            description = null,
            attribution = "",
            isPrivate = false,
            ownerId = "user-1",
            ownerDisplayName = "User",
            bookCount = 0,
            totalDurationSeconds = 0,
            createdAtMs = 0,
            updatedAtMs = 0,
        )

        /** Fixture wiring every [StorySoFarViewModel] dependency as an in-memory fake. */
        class Fixture(
            initialPositions: Map<String, PlaybackPosition> = emptyMap(),
        ) {
            val eventRepo = FakeWorldEventEditRepository()
            val entityRepo = FakeEntityEditRepository()
            val seriesRepo = FakeSeriesRepository()
            val bookRepo = FakeBookRepository()
            val positionRepo = FakePlaybackPositionRepository(initialPositions)
            val readingOrderRepo = FakeReadingOrderRepository()
            val playbackManager = FakePlaybackManager()

            fun build() =
                StorySoFarViewModel(
                    worldEventEditRepository = eventRepo,
                    entityEditRepository = entityRepo,
                    seriesRepository = seriesRepo,
                    bookRepository = bookRepo,
                    playbackPositionRepository = positionRepo,
                    readingOrderRepository = readingOrderRepo,
                    playbackManager = playbackManager,
                )

            fun seedSeries(
                seriesId: String = "series-1",
                books: List<BookListItem> = listOf(book("book-1", "The Way of Kings")),
            ) {
                seriesRepo.setSeriesWithBooks(
                    seriesId,
                    SeriesWithBooks(
                        series = series(id = seriesId),
                        books = books,
                        bookSequences = books.mapIndexed { index, b -> b.id.value to (index + 1).toString() }.toMap(),
                    ),
                )
            }
        }

        test("ordered clock chosen when active order set: events from a second ordered book fold in, orderName populated") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf(
                                "book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L),
                                "book-2" to position("book-2", maxPositionMs = 50_000L, startedAtMs = 500L),
                            ),
                    )
                fixture.seedSeries(books = listOf(book("book-1", "Book One"), book("book-2", "Book Two")))
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin", EntityKind.CHARACTER)))
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e1", "Kaladin practices.", mentionIds = listOf("char-1"), bookId = "book-1", positionMs = 10_000L),
                        event("e2", "Kaladin arrives.", mentionIds = listOf("char-1"), bookId = "book-2", positionMs = 5_000L),
                    ),
                )
                val order = readingOrder("order-1", "Cosmere Order")
                fixture.readingOrderRepo.setMyOrders(listOf(order))
                fixture.readingOrderRepo.setBookIds(order.id, listOf(BookId("book-1"), BookId("book-2")))
                fixture.readingOrderRepo.setActiveOrder("series-1", order.id)
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StorySoFarUiState.Idle
                    viewModel.load("book-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe StorySoFarUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    ready.orderName shouldBe "Cosmere Order"
                    ready.standRows.map { it.entity.name } shouldBe listOf("Kaladin")
                    // Folded in from book-2 (out of PerBookClock scope, in OrderedClock scope).
                    ready.standRows
                        .single()
                        .lastSeenAnchor
                        .shouldBeInstanceOf<AnchorLabel.AtTime>()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("no active order + multi-book series: PerBookClock scoping excludes other-book events, noOrderFloor true") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf(
                                "book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L),
                                "book-2" to position("book-2", maxPositionMs = 100_000L, startedAtMs = 1_000L),
                            ),
                    )
                fixture.seedSeries(books = listOf(book("book-1", "Book One"), book("book-2", "Book Two")))
                fixture.entityRepo.setEntities(
                    listOf(
                        entity("char-1", "Kaladin", EntityKind.CHARACTER),
                        entity("char-2", "Shallan", EntityKind.CHARACTER),
                    ),
                )
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e1", "Kaladin practices.", mentionIds = listOf("char-1"), bookId = "book-1", positionMs = 1_000L),
                        event("e2", "Shallan draws.", mentionIds = listOf("char-2"), bookId = "book-2", positionMs = 1_000L),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StorySoFarUiState.Idle
                    viewModel.load("book-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe StorySoFarUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    ready.noOrderFloor shouldBe true
                    // book-2's event (and Shallan) is out of PerBookClock scope entirely.
                    ready.standRows.map { it.entity.name } shouldBe listOf("Kaladin")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("single-book series: noOrderFloor is false") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.seedSeries(books = listOf(book("book-1", "Book One")))
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin", EntityKind.CHARACTER)))
                fixture.eventRepo.setEvents(
                    listOf(event("e1", "Kaladin practices.", mentionIds = listOf("char-1"), positionMs = 1_000L)),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StorySoFarUiState.Idle
                    viewModel.load("book-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe StorySoFarUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    ready.noOrderFloor shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("standalone book: floor false and orderName null") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.bookRepo.setBooks(listOf(book("book-1", "Solo Book")))
                fixture.entityRepo.setEntities(
                    listOf(entity("char-1", "Kaladin", EntityKind.CHARACTER, seriesId = null, bookId = "book-1")),
                )
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e1",
                            "Kaladin practices.",
                            mentionIds = listOf("char-1"),
                            positionMs = 1_000L,
                            seriesId = null,
                            homeBookId = "book-1",
                        ),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StorySoFarUiState.Idle
                    viewModel.load("book-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe StorySoFarUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    ready.noOrderFloor shouldBe false
                    ready.orderName shouldBe null
                    ready.seriesId shouldBe null
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("EmptyFloor when no safe events") {
            runTest {
                val fixture = Fixture(initialPositions = emptyMap())
                fixture.bookRepo.setBooks(listOf(book("book-1", "Solo Book")))
                fixture.entityRepo.setEntities(
                    listOf(entity("char-1", "Kaladin", EntityKind.CHARACTER, seriesId = null, bookId = "book-1")),
                )
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e1",
                            "Kaladin practices.",
                            mentionIds = listOf("char-1"),
                            positionMs = 10_000L,
                            seriesId = null,
                            homeBookId = "book-1",
                        ),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StorySoFarUiState.Idle
                    viewModel.load("book-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe StorySoFarUiState.Loading

                    val empty = awaitItem().shouldBeInstanceOf<StorySoFarUiState.EmptyFloor>()
                    empty.bookTitle shouldBe "Solo Book"
                    empty.bookId shouldBe "book-1"
                    empty.seriesId shouldBe null
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("asOfHere widens an EmptyFloor into Ready, with the newly-visible entity marked isNew") {
            runTest {
                val fixture = Fixture(initialPositions = emptyMap())
                fixture.bookRepo.setBooks(listOf(book("book-1", "Solo Book")))
                fixture.entityRepo.setEntities(
                    listOf(entity("char-1", "Kaladin", EntityKind.CHARACTER, seriesId = null, bookId = "book-1")),
                )
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e1",
                            "Kaladin practices.",
                            mentionIds = listOf("char-1"),
                            positionMs = 10_000L,
                            seriesId = null,
                            homeBookId = "book-1",
                        ),
                    ),
                )
                fixture.playbackManager.currentBookIdFlow.value = BookId("book-1")
                fixture.playbackManager.currentPositionMsFlow.value = 50_000L
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StorySoFarUiState.Idle
                    viewModel.load("book-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe StorySoFarUiState.Loading
                    awaitItem().shouldBeInstanceOf<StorySoFarUiState.EmptyFloor>()

                    viewModel.setAsOfHere(true)
                    advanceUntilIdle()
                    val ready = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    ready.standRows.single().isNew shouldBe true
                    ready.asOfHere shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("asOfHere is unavailable when playing another book") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.bookRepo.setBooks(listOf(book("book-1", "Solo Book")))
                fixture.entityRepo.setEntities(
                    listOf(entity("char-1", "Kaladin", EntityKind.CHARACTER, seriesId = null, bookId = "book-1")),
                )
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e1",
                            "Kaladin practices.",
                            mentionIds = listOf("char-1"),
                            positionMs = 1_000L,
                            seriesId = null,
                            homeBookId = "book-1",
                        ),
                    ),
                )
                fixture.playbackManager.currentBookIdFlow.value = BookId("other-book")
                fixture.playbackManager.currentPositionMsFlow.value = 999_000L
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StorySoFarUiState.Idle
                    viewModel.load("book-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe StorySoFarUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    ready.asOfHereAvailable shouldBe false
                    ready.hereLabel shouldBe null

                    // Toggling asOfHere on flips the raw flag, but availability still gates widening —
                    // the same single row surfaces, just now with asOfHere = true.
                    viewModel.setAsOfHere(true)
                    advanceUntilIdle()
                    val toggled = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    toggled.asOfHere shouldBe true
                    toggled.asOfHereAvailable shouldBe false
                    toggled.hereLabel shouldBe null
                    toggled.standRows.map { it.entity.name } shouldBe listOf("Kaladin")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("toggling asOfHere on widens rows in, off restores the original set") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 20_000L, startedAtMs = 1_000L)),
                    )
                fixture.bookRepo.setBooks(listOf(book("book-1", "Solo Book")))
                fixture.entityRepo.setEntities(
                    listOf(
                        entity("char-1", "Kaladin", EntityKind.CHARACTER, seriesId = null, bookId = "book-1"),
                        entity("char-2", "Shallan", EntityKind.CHARACTER, seriesId = null, bookId = "book-1"),
                    ),
                )
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e1",
                            "Kaladin practices.",
                            mentionIds = listOf("char-1"),
                            positionMs = 5_000L,
                            seriesId = null,
                            homeBookId = "book-1",
                        ),
                        event(
                            "e2",
                            "Shallan draws.",
                            mentionIds = listOf("char-2"),
                            positionMs = 50_000L,
                            seriesId = null,
                            homeBookId = "book-1",
                        ),
                    ),
                )
                fixture.playbackManager.currentBookIdFlow.value = BookId("book-1")
                fixture.playbackManager.currentPositionMsFlow.value = 60_000L
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StorySoFarUiState.Idle
                    viewModel.load("book-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe StorySoFarUiState.Loading

                    val initial = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    initial.standRows.map { it.entity.name } shouldBe listOf("Kaladin")

                    viewModel.setAsOfHere(true)
                    advanceUntilIdle()
                    val widened = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    // Fold recency is latest-first: Shallan's mention (positionMs 50_000) is more
                    // recent than Kaladin's (positionMs 5_000), so she sorts first within the group.
                    widened.standRows.map { it.entity.name } shouldBe listOf("Shallan", "Kaladin")
                    widened.standRows.first { it.entity.name == "Shallan" }.isNew shouldBe true
                    widened.standRows.first { it.entity.name == "Kaladin" }.isNew shouldBe false

                    viewModel.setAsOfHere(false)
                    advanceUntilIdle()
                    val restored = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    restored.standRows.map { it.entity.name } shouldBe listOf("Kaladin")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a fresh ViewModel starts with asOfHere off") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.bookRepo.setBooks(listOf(book("book-1", "Solo Book")))
                fixture.entityRepo.setEntities(
                    listOf(entity("char-1", "Kaladin", EntityKind.CHARACTER, seriesId = null, bookId = "book-1")),
                )
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e1",
                            "Kaladin practices.",
                            mentionIds = listOf("char-1"),
                            positionMs = 1_000L,
                            seriesId = null,
                            homeBookId = "book-1",
                        ),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StorySoFarUiState.Idle
                    viewModel.load("book-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe StorySoFarUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    ready.asOfHere shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("statusLine resolves mention tokens to live entity names") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.bookRepo.setBooks(listOf(book("book-1", "Solo Book")))
                fixture.entityRepo.setEntities(
                    listOf(
                        entity("char-1", "Kaladin", EntityKind.CHARACTER, seriesId = null, bookId = "book-1"),
                        entity("char-2", "Shallan", EntityKind.CHARACTER, seriesId = null, bookId = "book-1"),
                    ),
                )
                val text =
                    "${MentionTokens.token("char-2", "Shallan")} warns ${MentionTokens.token("char-1", "Kaladin")}."
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e1",
                            text,
                            mentionIds = listOf("char-1", "char-2"),
                            positionMs = 1_000L,
                            seriesId = null,
                            homeBookId = "book-1",
                            subjectEntityId = "char-1",
                        ),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StorySoFarUiState.Idle
                    viewModel.load("book-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe StorySoFarUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    val kaladinRow = ready.standRows.first { it.entity.name == "Kaladin" }
                    kaladinRow.statusLine shouldBe "Shallan warns Kaladin."
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a DEPARTS event marks its subject enRoute with the departed-from entity's name") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.bookRepo.setBooks(listOf(book("book-1", "Solo Book")))
                fixture.entityRepo.setEntities(
                    listOf(
                        entity("char-1", "Kaladin", EntityKind.CHARACTER, seriesId = null, bookId = "book-1"),
                        entity("loc-1", "Urithiru", EntityKind.LOCATION, seriesId = null, bookId = "book-1"),
                    ),
                )
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e1",
                            "Kaladin departs.",
                            mentionIds = listOf("char-1", "loc-1"),
                            positionMs = 1_000L,
                            type = WorldEventType.DEPARTS,
                            seriesId = null,
                            homeBookId = "book-1",
                            subjectEntityId = "char-1",
                            objectEntityId = "loc-1",
                        ),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StorySoFarUiState.Idle
                    viewModel.load("book-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe StorySoFarUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    val kaladinRow = ready.standRows.first { it.entity.name == "Kaladin" }
                    kaladinRow.enRoute shouldBe true
                    kaladinRow.enRouteFrom shouldBe "Urithiru"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("CHARACTER rows come before LOCATION/ITEM rows") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.bookRepo.setBooks(listOf(book("book-1", "Solo Book")))
                fixture.entityRepo.setEntities(
                    listOf(
                        entity("item-1", "Shardblade", EntityKind.ITEM, seriesId = null, bookId = "book-1"),
                        entity("loc-1", "Urithiru", EntityKind.LOCATION, seriesId = null, bookId = "book-1"),
                        entity("char-1", "Kaladin", EntityKind.CHARACTER, seriesId = null, bookId = "book-1"),
                    ),
                )
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e1",
                            "The item appears.",
                            mentionIds = listOf("item-1"),
                            positionMs = 1_000L,
                            seriesId = null,
                            homeBookId = "book-1",
                        ),
                        event(
                            "e2",
                            "The location is described.",
                            mentionIds = listOf("loc-1"),
                            positionMs = 2_000L,
                            seriesId = null,
                            homeBookId = "book-1",
                        ),
                        event(
                            "e3",
                            "Kaladin practices.",
                            mentionIds = listOf("char-1"),
                            positionMs = 3_000L,
                            seriesId = null,
                            homeBookId = "book-1",
                        ),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StorySoFarUiState.Idle
                    viewModel.load("book-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe StorySoFarUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    ready.standRows.map { it.entity.kind } shouldBe
                        listOf(EntityKind.CHARACTER, EntityKind.LOCATION, EntityKind.ITEM)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("renaming the active reading order is reflected in orderName") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1_000L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin", EntityKind.CHARACTER)))
                fixture.eventRepo.setEvents(
                    listOf(event("e1", "Kaladin practices.", mentionIds = listOf("char-1"), positionMs = 1_000L)),
                )
                val order = readingOrder("order-1", "Old Name")
                fixture.readingOrderRepo.setMyOrders(listOf(order))
                fixture.readingOrderRepo.setBookIds(order.id, listOf(BookId("book-1")))
                fixture.readingOrderRepo.setActiveOrder("series-1", order.id)
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StorySoFarUiState.Idle
                    viewModel.load("book-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe StorySoFarUiState.Loading
                    val before = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    before.orderName shouldBe "Old Name"

                    fixture.readingOrderRepo.setMyOrders(listOf(order.copy(name = "New Name")))
                    advanceUntilIdle()

                    val after = awaitItem().shouldBeInstanceOf<StorySoFarUiState.Ready>()
                    after.orderName shouldBe "New Name"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
