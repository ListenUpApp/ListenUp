package com.calypsan.listenup.client.presentation.storyworld.composer

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.Entity
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.model.WorldEvent
import com.calypsan.listenup.client.presentation.storyworld.AnchorLabel
import com.calypsan.listenup.client.presentation.storyworld.EntityCard
import com.calypsan.listenup.client.presentation.storyworld.WorldRef
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.client.test.fake.FakeEntityEditRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackManager
import com.calypsan.listenup.client.test.fake.FakeSeriesRepository
import com.calypsan.listenup.client.test.fake.FakeWorldEventEditRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
 * Tests for [WorldComposerViewModel]: default-anchor derivation from playback, mention/verb
 * trigger suggestions and quick-create, the assertion chip's stored-columns-until-first-edit
 * rule, dismiss/redetect semantics, and the exact create/edit save shapes.
 *
 * [WorldComposerViewModel.state] is a `combine(...).stateIn(WhileSubscribed)` flow, so every test
 * keeps a live [app.cash.turbine.testIn] subscription (`states`) open for its duration — reading
 * `.value` directly would never see anything past the initial value, since nothing ever starts
 * collecting the upstream `combine` without an active subscriber.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorldComposerViewModelTest :
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

        fun storedEvent(
            id: String = "event-1",
            text: String,
            type: WorldEventType = WorldEventType.NOTE,
            bookId: String? = null,
            positionMs: Long? = null,
            subjectEntityId: String? = null,
            objectEntityId: String? = null,
            seriesId: String = "series-1",
        ) = WorldEvent(
            id = id,
            homeSeriesId = seriesId,
            bookId = bookId,
            positionMs = positionMs,
            type = type,
            text = text,
            subjectEntityId = subjectEntityId,
            objectEntityId = objectEntityId,
            source = WorldEventSource.MANUAL,
        )

        /** Wires every [WorldComposerViewModel] dependency as an in-memory fake. */
        class Fixture {
            val entityRepo = FakeEntityEditRepository()
            val eventRepo = FakeWorldEventEditRepository()
            val seriesRepo = FakeSeriesRepository()
            val bookRepo = FakeBookRepository()
            val playbackManager = FakePlaybackManager()
            val errorBus = ErrorBus()

            fun build() =
                WorldComposerViewModel(
                    entityEditRepository = entityRepo,
                    worldEventEditRepository = eventRepo,
                    seriesRepository = seriesRepo,
                    bookRepository = bookRepo,
                    playbackManager = playbackManager,
                    errorBus = errorBus,
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

        val world = WorldRef(seriesId = "series-1")

        test("default anchor is Playhead when the world's book is currently playing") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.playbackManager.currentBookIdFlow.value = BookId("book-1")
                fixture.playbackManager.currentPositionMsFlow.value = 42_000L
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    states.expectMostRecentItem().anchor shouldBe AnchorSelection.Playhead("book-1", 42_000L)
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("default anchor is AlwaysVisible when nothing is playing") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    states.expectMostRecentItem().anchor shouldBe AnchorSelection.AlwaysVisible
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("default anchor is AlwaysVisible when playing a book outside the world") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.playbackManager.currentBookIdFlow.value = BookId("other-book")
                fixture.playbackManager.currentPositionMsFlow.value = 10_000L
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    states.expectMostRecentItem().anchor shouldBe AnchorSelection.AlwaysVisible
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("mention suggestions filter by name and quickCreate hides on an exact match") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("e-kal", "Kaladin"), entity("e-sha", "Shallan")))
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    viewModel.onDisplayChanged("@kal", 4)
                    advanceUntilIdle()
                    val filtered = states.expectMostRecentItem()
                    filtered.suggestions.map { it.name } shouldBe listOf("Kaladin")
                    filtered.showQuickCreate shouldBe true
                    filtered.quickCreateQuery shouldBe "kal"

                    viewModel.onDisplayChanged("@Kaladin", 8)
                    advanceUntilIdle()
                    states.expectMostRecentItem().showQuickCreate shouldBe false
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("verb suggestions filter by prefix on an open * trigger") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    viewModel.onDisplayChanged("*mov", 4)
                    advanceUntilIdle()

                    states.expectMostRecentItem().verbSuggestions shouldBe listOf("moves to")
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("quickCreate mints an entity and inserts it as a mention, and its token survives save") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    viewModel.onDisplayChanged("@Renarin", 8)
                    advanceUntilIdle()
                    states.expectMostRecentItem().showQuickCreate shouldBe true

                    viewModel.quickCreate("Renarin", EntityKind.CHARACTER)
                    advanceUntilIdle()
                    states.expectMostRecentItem().displayText shouldBe "Renarin"

                    viewModel.save()
                    advanceUntilIdle()
                    states.cancelAndIgnoreRemainingEvents()
                }

                fixture.eventRepo.observeForWorld("series-1", null).test {
                    awaitItem().single().text shouldBe "[[e:fake-entity-0|Renarin]]"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("acceptVerb replaces the open trigger with the verb phrase plus a trailing space") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    viewModel.onDisplayChanged("*mov", 4)
                    advanceUntilIdle()
                    viewModel.acceptVerb("moves to")
                    advanceUntilIdle()

                    val result = states.expectMostRecentItem()
                    result.displayText shouldBe "moves to "
                    result.cursor shouldBe "moves to ".length
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a two-mention verb pattern surfaces the MOVES_TO assertion chip") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.entityRepo.setEntities(
                    listOf(entity("e-a", "Eddard"), entity("e-b", "Winterfell", EntityKind.LOCATION)),
                )
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    viewModel.acceptMention(EntityCard("e-a", "Eddard", EntityKind.CHARACTER))
                    advanceUntilIdle()
                    viewModel.onDisplayChanged("Eddard moves to ", "Eddard moves to ".length)
                    advanceUntilIdle()
                    viewModel.acceptMention(EntityCard("e-b", "Winterfell", EntityKind.LOCATION))
                    advanceUntilIdle()

                    val assertion = states.expectMostRecentItem().assertion.shouldNotBeNull()
                    assertion.type shouldBe WorldEventType.MOVES_TO
                    assertion.subjectName shouldBe "Eddard"
                    assertion.objectName shouldBe "Winterfell"
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("dismissAssertion clears the chip and save writes a NOTE with no subject/object but full prose") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.entityRepo.setEntities(
                    listOf(entity("e-a", "Eddard"), entity("e-b", "Winterfell", EntityKind.LOCATION)),
                )
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()
                    viewModel.acceptMention(EntityCard("e-a", "Eddard", EntityKind.CHARACTER))
                    advanceUntilIdle()
                    viewModel.onDisplayChanged("Eddard moves to ", "Eddard moves to ".length)
                    advanceUntilIdle()
                    viewModel.acceptMention(EntityCard("e-b", "Winterfell", EntityKind.LOCATION))
                    advanceUntilIdle()

                    viewModel.dismissAssertion()
                    advanceUntilIdle()
                    states.expectMostRecentItem().assertion.shouldBeNull()

                    viewModel.save()
                    advanceUntilIdle()
                    states.cancelAndIgnoreRemainingEvents()
                }

                fixture.eventRepo.observeForWorld("series-1", null).test {
                    val saved = awaitItem().single()
                    saved.type shouldBe WorldEventType.NOTE
                    saved.subjectEntityId.shouldBeNull()
                    saved.objectEntityId.shouldBeNull()
                    saved.text shouldBe "[[e:e-a|Eddard]] moves to [[e:e-b|Winterfell]]"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a dismissed assertion stays dismissed after an edit that doesn't change the parse") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.entityRepo.setEntities(
                    listOf(entity("e-a", "Eddard"), entity("e-b", "Winterfell", EntityKind.LOCATION)),
                )
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()
                    viewModel.acceptMention(EntityCard("e-a", "Eddard", EntityKind.CHARACTER))
                    advanceUntilIdle()
                    viewModel.onDisplayChanged("Eddard moves to ", "Eddard moves to ".length)
                    advanceUntilIdle()
                    viewModel.acceptMention(EntityCard("e-b", "Winterfell", EntityKind.LOCATION))
                    advanceUntilIdle()
                    viewModel.dismissAssertion()
                    advanceUntilIdle()

                    val unrelatedEdit = "Eddard moves to Winterfell today"
                    viewModel.onDisplayChanged(unrelatedEdit, unrelatedEdit.length)
                    advanceUntilIdle()

                    states.expectMostRecentItem().assertion.shouldBeNull()
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a dismissed assertion resets once the parsed assertion changes") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.entityRepo.setEntities(
                    listOf(
                        entity("e-a", "Eddard"),
                        entity("e-b", "Winterfell", EntityKind.LOCATION),
                        entity("e-c", "Catelyn"),
                    ),
                )
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()
                    viewModel.acceptMention(EntityCard("e-a", "Eddard", EntityKind.CHARACTER))
                    advanceUntilIdle()
                    viewModel.onDisplayChanged("Eddard moves to ", "Eddard moves to ".length)
                    advanceUntilIdle()
                    viewModel.acceptMention(EntityCard("e-b", "Winterfell", EntityKind.LOCATION))
                    advanceUntilIdle()
                    viewModel.dismissAssertion()
                    advanceUntilIdle()

                    // Clear the note and rebuild it with a different subject — the parsed assertion changes.
                    viewModel.onDisplayChanged("", 0)
                    advanceUntilIdle()
                    viewModel.acceptMention(EntityCard("e-c", "Catelyn", EntityKind.CHARACTER))
                    advanceUntilIdle()
                    viewModel.onDisplayChanged("Catelyn moves to ", "Catelyn moves to ".length)
                    advanceUntilIdle()
                    viewModel.acceptMention(EntityCard("e-b", "Winterfell", EntityKind.LOCATION))
                    advanceUntilIdle()

                    val assertion = states.expectMostRecentItem().assertion.shouldNotBeNull()
                    assertion.type shouldBe WorldEventType.MOVES_TO
                    assertion.subjectName shouldBe "Catelyn"
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("save in create mode records the exact NewWorldEvent shape") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.playbackManager.currentBookIdFlow.value = BookId("book-1")
                fixture.playbackManager.currentPositionMsFlow.value = 5_000L
                fixture.entityRepo.setEntities(
                    listOf(entity("e-a", "Eddard"), entity("e-b", "Winterfell", EntityKind.LOCATION)),
                )
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()
                    viewModel.acceptMention(EntityCard("e-a", "Eddard", EntityKind.CHARACTER))
                    advanceUntilIdle()
                    viewModel.onDisplayChanged("Eddard departs", "Eddard departs".length)
                    advanceUntilIdle()

                    viewModel.save()
                    advanceUntilIdle()
                    states.cancelAndIgnoreRemainingEvents()
                }

                fixture.eventRepo.observeForWorld("series-1", null).test {
                    val saved = awaitItem().single()
                    saved.homeSeriesId shouldBe "series-1"
                    saved.homeBookId.shouldBeNull()
                    saved.bookId shouldBe "book-1"
                    saved.positionMs shouldBe 5_000L
                    saved.type shouldBe WorldEventType.DEPARTS
                    saved.text shouldBe "[[e:e-a|Eddard]] departs"
                    saved.subjectEntityId shouldBe "e-a"
                    saved.objectEntityId.shouldBeNull()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("save in edit mode updates the exact WorldEventUpsert shape, preserving the id") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("e-a", "Eddard")))
                fixture.eventRepo.setEvents(listOf(storedEvent(id = "event-1", text = "An early draft.")))
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world, editEventId = "event-1")
                    advanceUntilIdle()

                    // Replace the stored plain-prose text with a fresh mention + verb.
                    viewModel.onDisplayChanged("", 0)
                    advanceUntilIdle()
                    viewModel.acceptMention(EntityCard("e-a", "Eddard", EntityKind.CHARACTER))
                    advanceUntilIdle()
                    viewModel.onDisplayChanged("Eddard departs", "Eddard departs".length)
                    advanceUntilIdle()

                    viewModel.save()
                    advanceUntilIdle()
                    states.cancelAndIgnoreRemainingEvents()
                }

                fixture.eventRepo.observeEvent("event-1").test {
                    val saved = awaitItem().shouldNotBeNull()
                    saved.id shouldBe "event-1"
                    saved.homeSeriesId shouldBe "series-1"
                    saved.type shouldBe WorldEventType.DEPARTS
                    saved.text shouldBe "[[e:e-a|Eddard]] departs"
                    saved.subjectEntityId shouldBe "e-a"
                    saved.objectEntityId.shouldBeNull()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("edit-mode seeding renders stored mention-token text with the live entity name") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Stormblessed")))
                fixture.eventRepo.setEvents(
                    listOf(storedEvent(id = "event-1", text = "[[e:char-1|Kaladin]] practices.")),
                )
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world, editEventId = "event-1")
                    advanceUntilIdle()

                    val loaded = states.expectMostRecentItem()
                    loaded.displayText shouldBe "Stormblessed practices."
                    loaded.isEditMode shouldBe true
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("edit-mode seeding shows the stored typed columns as the chip even when the text wouldn't parse") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(
                        storedEvent(
                            id = "event-1",
                            text = "Plain prose that mentions nobody.",
                            type = WorldEventType.DEPARTS,
                            subjectEntityId = "char-1",
                        ),
                    ),
                )
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world, editEventId = "event-1")
                    advanceUntilIdle()

                    val assertion = states.expectMostRecentItem().assertion.shouldNotBeNull()
                    assertion.type shouldBe WorldEventType.DEPARTS
                    assertion.subjectName shouldBe "Kaladin"
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("canSave is false for an empty document with no assertion") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    states.expectMostRecentItem().canSave shouldBe false
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("canSave is true for a stored assertion even when the note text is blank") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.entityRepo.setEntities(listOf(entity("char-1", "Kaladin")))
                fixture.eventRepo.setEvents(
                    listOf(
                        storedEvent(
                            id = "event-1",
                            text = "",
                            type = WorldEventType.DEPARTS,
                            subjectEntityId = "char-1",
                        ),
                    ),
                )
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world, editEventId = "event-1")
                    advanceUntilIdle()

                    val loaded = states.expectMostRecentItem()
                    loaded.displayText shouldBe ""
                    loaded.canSave shouldBe true
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("anchorSummary resolves AlwaysVisible when the anchor carries no book") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    states.expectMostRecentItem().anchorSummary shouldBe AnchorLabel.AlwaysVisible
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("worldBooks exposes this world's books in reading order with sequence label and duration") {
            runTest {
                val fixture = Fixture()
                fixture.seriesRepo.setSeriesWithBooks(
                    "series-1",
                    SeriesWithBooks(
                        series = series(),
                        books = listOf(book("book-1", "The Way of Kings"), book("book-2", "Words of Radiance")),
                        bookSequences = mapOf("book-1" to "1", "book-2" to "2"),
                    ),
                )
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    val worldBooks = states.expectMostRecentItem().worldBooks
                    worldBooks.map { it.id } shouldBe listOf("book-1", "book-2")
                    worldBooks[0].sequenceLabel shouldBe "1"
                    worldBooks[1].sequenceLabel shouldBe "2"
                    worldBooks[0].durationMs shouldBe 3_600_000L
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("playheadSnapshot and playheadLabel are exposed independent of the currently selected anchor") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.playbackManager.currentBookIdFlow.value = BookId("book-1")
                fixture.playbackManager.currentPositionMsFlow.value = 42_000L
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    // The author picks a different anchor — the playhead card should still be offered.
                    viewModel.selectAnchor(AnchorSelection.AlwaysVisible)
                    advanceUntilIdle()

                    val ready = states.expectMostRecentItem()
                    ready.anchor shouldBe AnchorSelection.AlwaysVisible
                    ready.playheadSnapshot shouldBe AnchorSelection.Playhead("book-1", 42_000L)
                    ready.playheadLabel.shouldNotBeNull()
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("playheadSnapshot is null when playing a book outside the world") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.playbackManager.currentBookIdFlow.value = BookId("other-book")
                fixture.playbackManager.currentPositionMsFlow.value = 10_000L
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    states.expectMostRecentItem().playheadSnapshot.shouldBeNull()
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("endOfChapterOption resolves the containing chapter's end from the live playhead") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                fixture.bookRepo.setChapters(
                    "book-1",
                    listOf(
                        Chapter(id = "ch-1", title = "Chapter 1", duration = 60_000L, startTime = 0L),
                        Chapter(id = "ch-2", title = "Chapter 2", duration = 60_000L, startTime = 60_000L),
                    ),
                )
                fixture.playbackManager.currentBookIdFlow.value = BookId("book-1")
                fixture.playbackManager.currentPositionMsFlow.value = 90_000L
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    states.expectMostRecentItem().endOfChapterOption shouldBe
                        AnchorSelection.EndOfCurrentChapter("book-1", 120_000L)
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("endOfChapterOption is null when nothing is playing") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    states.expectMostRecentItem().endOfChapterOption.shouldBeNull()
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("worldBookChapters maps every world book to its own chapters") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries(books = listOf(book("book-1", "Book One"), book("book-2", "Book Two")))
                fixture.bookRepo.setChapters(
                    "book-1",
                    listOf(Chapter(id = "ch-1", title = "Ch 1", duration = 10_000L, startTime = 0L)),
                )
                fixture.bookRepo.setChapters(
                    "book-2",
                    listOf(Chapter(id = "ch-2", title = "Ch 2", duration = 20_000L, startTime = 0L)),
                )
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    val chaptersByBook = states.expectMostRecentItem().worldBookChapters
                    chaptersByBook["book-1"]?.single()?.id shouldBe "ch-1"
                    chaptersByBook["book-2"]?.single()?.id shouldBe "ch-2"
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("previewAnchorLabel resolves a label for an arbitrary book/position without committing the anchor") {
            runTest {
                val fixture = Fixture()
                fixture.seedSeries(books = listOf(book("book-1", "The Way of Kings")))
                fixture.bookRepo.setChapters(
                    "book-1",
                    listOf(Chapter(id = "ch-1", title = "Prelude", duration = 60_000L, startTime = 0L)),
                )
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)

                    viewModel.start(world)
                    advanceUntilIdle()

                    val label = viewModel.previewAnchorLabel("book-1", 30_000L)
                    label.shouldBeInstanceOf<AnchorLabel.AtChapter>()
                    (label as AnchorLabel.AtChapter).chapterTitle shouldBe "Prelude"

                    // The committed anchor is untouched by a preview lookup.
                    states.expectMostRecentItem().anchor shouldBe AnchorSelection.AlwaysVisible
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
