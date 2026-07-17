package com.calypsan.listenup.client.presentation.storyworld

import app.cash.turbine.test
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
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
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [EntityDetailViewModel]. Pins the chronological (world-clock, not write-recency)
 * ordering of an entity's log, frontier gating, mention-token rendering, the unstarted-books
 * banner, and the rename/delete/deleteEntry action shapes (including the ErrorBus failure branch).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntityDetailViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun entity(
            id: String,
            name: String,
            kind: EntityKind = EntityKind.CHARACTER,
            seriesId: String = "series-1",
        ) = Entity(id = id, kind = kind, name = name, homeSeriesId = seriesId)

        fun event(
            id: String,
            text: String = "an event",
            mentionIds: List<String> = listOf("char-1"),
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

        /** Fixture wiring every [EntityDetailViewModel] dependency as an in-memory fake. */
        class Fixture(
            initialPositions: Map<String, PlaybackPosition> = emptyMap(),
        ) {
            val entityRepo = FakeEntityEditRepository()
            val eventRepo = FakeWorldEventEditRepository()
            val seriesRepo = FakeSeriesRepository()
            val bookRepo = FakeBookRepository()
            val positionRepo = FakePlaybackPositionRepository(initialPositions)
            val errorBus = ErrorBus()

            fun build() =
                EntityDetailViewModel(
                    entityEditRepository = entityRepo,
                    worldEventEditRepository = eventRepo,
                    seriesRepository = seriesRepo,
                    bookRepository = bookRepo,
                    playbackPositionRepository = positionRepo,
                    errorBus = errorBus,
                )

            fun seedSeries(books: List<BookListItem> = listOf(book("book-1", "The Way of Kings"))) {
                seriesRepo.setSeriesWithBooks(
                    "series-1",
                    SeriesWithBooks(
                        series = series(),
                        books = books,
                        bookSequences = books.mapIndexed { index, b -> b.id.value to (index + 1).toString() }.toMap(),
                    ),
                )
            }
        }

        test("load emits NotFound when the entity is absent") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("missing-entity")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    awaitItem() shouldBe EntityDetailUiState.NotFound
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("baseline entries (no book anchor) come before anchored entries, stable by id") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e-anchored", bookId = "book-1", positionMs = 1_000L),
                        event("e-baseline-b", bookId = null),
                        event("e-baseline-a", bookId = null),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    ready.entries.map { it.id } shouldBe listOf("e-baseline-a", "e-baseline-b", "e-anchored")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("entries anchored to different books order by series sequence, not input recency") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf(
                                "book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L),
                                "book-2" to position("book-2", maxPositionMs = 100_000L, startedAtMs = 1L),
                            ),
                    )
                fixture.seedSeries(books = listOf(book("book-1", "Book One"), book("book-2", "Book Two")))
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                // Recency-sorted DESC input order: the book-2 event is "most recent".
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e-book2", bookId = "book-2", positionMs = 1_000L),
                        event("e-book1", bookId = "book-1", positionMs = 1_000L),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    ready.entries.map { it.id } shouldBe listOf("e-book1", "e-book2")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("entries anchored to the same book order by positionMs") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e-late", bookId = "book-1", positionMs = 50_000L),
                        event("e-early", bookId = "book-1", positionMs = 1_000L),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    ready.entries.map { it.id } shouldBe listOf("e-early", "e-late")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("entries anchored to a book outside this world sort last") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf(
                                "book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L),
                                // Not part of this series' book roster, but the viewer has heard it.
                                "book-unknown" to
                                    position("book-unknown", maxPositionMs = 1_000_000L, startedAtMs = 1L),
                            ),
                    )
                fixture.seedSeries(books = listOf(book("book-1", "Book One")))
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e-unknown", bookId = "book-unknown", positionMs = 1_000L),
                        event("e-known", bookId = "book-1", positionMs = 50_000L),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    ready.entries.map { it.id } shouldBe listOf("e-known", "e-unknown")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("an entry beyond the frontier is hidden from entries and counted") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e-beyond", bookId = "book-1", positionMs = 500_000L),
                        event("e-visible", bookId = "book-1", positionMs = 50_000L),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    ready.entries.map { it.id } shouldBe listOf("e-visible")
                    ready.hiddenCount shouldBe 1
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("showHidden reveals the gated entry and keeps chronological order") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e-beyond", bookId = "book-1", positionMs = 500_000L),
                        event("e-visible", bookId = "book-1", positionMs = 50_000L),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()

                    viewModel.showHidden()
                    advanceUntilIdle()
                    val revealed = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    revealed.hiddenCount shouldBe 0
                    revealed.revealed shouldBe true
                    revealed.entries.map { it.id } shouldBe listOf("e-visible", "e-beyond")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("renderedText resolves mention tokens to the live entity name") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(
                    listOf(
                        entity("char-1", "Kaladin"),
                        entity("char-2", "Shallan"),
                    ),
                )
                fixture.eventRepo.setEvents(
                    listOf(
                        event(
                            "e1",
                            text = "[[e:char-1|Kaladin]] meets [[e:char-2|Shallan]].",
                            mentionIds = listOf("char-1", "char-2"),
                            bookId = "book-1",
                            positionMs = 1_000L,
                        ),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    ready.entries.single().renderedText shouldBe "Kaladin meets Shallan."
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("Ready exposes the entity's home world for the composer sheet") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin", seriesId = "series-1")))
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    ready.world shouldBe WorldRef(seriesId = "series-1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("rename preserves the entity's existing imageRef") {
            runTest {
                val fixture = Fixture()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin").copy(imageRef = "image-ref-1")))
                val viewModel = fixture.build()
                viewModel.load("char-1")

                viewModel.rename("Stormblessed")
                advanceUntilIdle()

                val updated = fixture.entityRepo.observeEntity("char-1").first()
                updated?.name shouldBe "Stormblessed"
                updated?.imageRef shouldBe "image-ref-1"
            }
        }

        test("deleteEntity success emits EntityDeleted") {
            runTest {
                val fixture = Fixture()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                val viewModel = fixture.build()
                viewModel.load("char-1")

                viewModel.events.test {
                    viewModel.deleteEntity()
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailEvent.EntityDeleted
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("deleteEntity failure emits typed AppError to ErrorBus") {
            runTest {
                val fixture = Fixture()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.entityRepo.deleteEntityResult = AppResult.Failure(ValidationError(message = "Cannot delete"))
                val viewModel = fixture.build()
                viewModel.load("char-1")

                fixture.errorBus.errors.test {
                    viewModel.deleteEntity()
                    advanceUntilIdle()
                    awaitItem().message shouldBe "Cannot delete"
                }
            }
        }

        test("deleteEntry delegates to the event repository") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(listOf(event("e1", bookId = "book-1", positionMs = 1_000L)))
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val initial = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    initial.entries.map { it.id } shouldBe listOf("e1")

                    viewModel.deleteEntry("e1")
                    advanceUntilIdle()
                    val after = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    after.entries shouldBe emptyList()
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
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    ready.unstartedBooksBanner shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("evolution splits into revealed/hidden matching the per-book frontier gate, order preserved") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e-late-hidden", bookId = "book-1", positionMs = 300_000L),
                        event("e-early-hidden", bookId = "book-1", positionMs = 200_000L),
                        event("e-visible", bookId = "book-1", positionMs = 10_000L),
                        event("e-baseline", bookId = null),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()

                    ready.evolution.revealed.map { it.eventId } shouldBe listOf("e-baseline", "e-visible")
                    ready.evolution.hidden.map { it.eventId } shouldBe listOf("e-early-hidden", "e-late-hidden")
                    // Matches the entries tab's own default (non-revealed) gate exactly.
                    ready.entries.map { it.id } shouldBe listOf("e-baseline", "e-visible")
                    ready.hiddenCount shouldBe 2
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("hidden evolution rows carry null text but resolve chapter-titled anchors, even for a book absent from entries") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L)),
                        // book-2 has no position row: any event anchored to it is hidden outright,
                        // so book-2 never appears in the entries tab's own visible-events set.
                    )
                fixture.seedSeries(books = listOf(book("book-1", "Book One"), book("book-2", "Book Two")))
                fixture.bookRepo.setChapters(
                    "book-2",
                    listOf(
                        Chapter(
                            id = "ch-2a",
                            title = "Interlude 1",
                            duration = 100_000L,
                            startTime = 0L,
                        ),
                    ),
                )
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e-book1", bookId = "book-1", positionMs = 1_000L),
                        event("e-book2-hidden", text = "a secret", bookId = "book-2", positionMs = 5_000L),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()

                    ready.entries.map { it.id } shouldBe listOf("e-book1")
                    val hiddenRow = ready.evolution.hidden.single { it.eventId == "e-book2-hidden" }
                    hiddenRow.renderedText shouldBe null
                    val anchor = hiddenRow.anchor.shouldBeInstanceOf<AnchorLabel.AtChapter>()
                    anchor.chapterTitle shouldBe "Interlude 1"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("isLatest is true only on the last revealed evolution row") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e-baseline", bookId = null),
                        event("e-early", bookId = "book-1", positionMs = 10_000L),
                        event("e-late", bookId = "book-1", positionMs = 50_000L),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()

                    ready.evolution.revealed.map { it.eventId to it.isLatest } shouldBe
                        listOf("e-baseline" to false, "e-early" to false, "e-late" to true)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("frontierLabel is the last book-anchored revealed row's anchor") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L)),
                    )
                fixture.seedSeries()
                fixture.bookRepo.setChapters(
                    "book-1",
                    listOf(
                        Chapter(
                            id = "ch-1",
                            title = "Prologue",
                            duration = 100_000L,
                            startTime = 0L,
                        ),
                    ),
                )
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e-baseline", bookId = null),
                        event("e-visible", bookId = "book-1", positionMs = 10_000L),
                        event("e-hidden", bookId = "book-1", positionMs = 200_000L),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()

                    ready.evolution.frontierLabel shouldBe AnchorLabel.AtChapter("1", "Prologue")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("frontierLabel falls back to the first hidden row's anchor when no revealed row has a book anchor") {
            runTest {
                // No position row for book-1 at all: every book-1-anchored event is hidden,
                // leaving only the baseline (anchor-less) entry revealed.
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e-baseline", bookId = null),
                        event("e-hidden-first", bookId = "book-1", positionMs = 0L),
                        event("e-hidden-second", bookId = "book-1", positionMs = 200_000L),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()

                    ready.evolution.revealed.map { it.eventId } shouldBe listOf("e-baseline")
                    ready.evolution.hidden.map { it.eventId } shouldBe
                        listOf("e-hidden-first", "e-hidden-second")
                    ready.evolution.frontierLabel shouldBe AnchorLabel.Beginning("1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("frontierLabel is null when nothing is hidden") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(event("e-visible", bookId = "book-1", positionMs = 10_000L)),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()

                    ready.evolution.hidden shouldBe emptyList()
                    ready.evolution.frontierLabel shouldBe null
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("showHidden reveals entries but leaves evolution unchanged") {
            runTest {
                val fixture =
                    Fixture(
                        initialPositions =
                            mapOf("book-1" to position("book-1", maxPositionMs = 100_000L, startedAtMs = 1L)),
                    )
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e-beyond", bookId = "book-1", positionMs = 500_000L),
                        event("e-visible", bookId = "book-1", positionMs = 50_000L),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val initial = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    initial.hiddenCount shouldBe 1

                    viewModel.showHidden()
                    advanceUntilIdle()
                    val revealed = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    revealed.hiddenCount shouldBe 0
                    revealed.entries.map { it.id } shouldBe listOf("e-visible", "e-beyond")
                    // Evolution never leaks the session reveal — it's the same in both states.
                    revealed.evolution shouldBe initial.evolution
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
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe EntityDetailUiState.Idle
                    viewModel.load("char-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EntityDetailUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<EntityDetailUiState.Ready>()
                    ready.unstartedBooksBanner shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
