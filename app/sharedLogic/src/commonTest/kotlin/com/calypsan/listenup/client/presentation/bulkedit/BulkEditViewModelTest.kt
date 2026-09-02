package com.calypsan.listenup.client.presentation.bulkedit

import app.cash.turbine.test
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.bulkedit.BulkEditApplier
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.client.domain.model.ContributorSearchResponse
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.SeriesSearchResponse
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.api.dto.BookUpdate
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private const val PUBLISHER = "Tor"

/**
 * The bulk editor's projection and its one destructive button.
 *
 * The property worth pinning: the preview count and what Apply does are computed from the same pure
 * function over the same books, so the number on screen cannot disagree with what happens.
 */
class BulkEditViewModelTest :
    FunSpec({

        // viewModelScope runs on Dispatchers.Main; without this the load coroutine never starts and
        // every state assertion fails against Loading rather than against what it is testing.
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }

        afterTest { Dispatchers.resetMain() }

        fun book(
            id: String,
            publisher: String? = null,
        ) = BookDetail(
            id = BookId(id),
            libraryId = LibraryId("lib"),
            folderId = FolderId("folder"),
            title = id,
            authors = emptyList(),
            narrators = emptyList(),
            duration = 1_000L,
            coverPath = null,
            addedAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
            publisher = publisher,
        )

        // A REAL applier over mocked repositories. BulkEditApplier is a final class, so Mokkery
        // cannot mock it — and building the real one is better anyway: it exercises the actual
        // action-to-repository routing rather than a stub that could drift from it.
        fun rig(
            books: List<BookDetail>,
            failOn: BookId? = null,
            requestedIds: List<String>? = null,
            libraryGenres: List<Genre> = emptyList(),
            libraryTags: List<Tag> = emptyList(),
            libraryMoods: List<Mood> = emptyList(),
            seriesMatches: List<SeriesSearchResult> = emptyList(),
        ): BulkEditViewModel {
            val byId = books.associateBy { it.id.value }
            val repo = mock<BookRepository>(MockMode.autoUnit)
            everySuspend { repo.getBookDetail(any()) } calls { (id: String) -> byId[id] }

            val edits = mock<BookEditRepository>(MockMode.autoUnit)
            val failure = AppResult.Failure(BookError.InvalidInput())
            everySuspend { edits.updateBook(any(), any()) } calls { (id: BookId, _: Any?) ->
                if (id == failOn) failure else AppResult.Success(Unit)
            }
            everySuspend { edits.setBookGenres(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { edits.setBookSeries(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { edits.setBookContributors(any(), any()) } returns AppResult.Success(Unit)

            val tags =
                mock<TagRepository>(MockMode.autoUnit) {
                    every { observeAllTags() } returns flowOf(libraryTags)
                }
            val moods =
                mock<MoodRepository>(MockMode.autoUnit) {
                    every { observeAllMoods() } returns flowOf(libraryMoods)
                }
            val genres =
                mock<GenreRepository>(MockMode.autoUnit) {
                    every { observeAll() } returns flowOf(libraryGenres)
                }
            val series =
                mock<SeriesRepository>(MockMode.autoUnit) {
                    every { observeAll() } returns flowOf(emptyList())
                }
            everySuspend { series.searchSeries(any(), any()) } returns
                SeriesSearchResponse(series = seriesMatches, isOfflineResult = true, tookMs = 0L)

            val contributors =
                mock<ContributorRepository>(MockMode.autoUnit) {
                    every { observeAll() } returns flowOf(emptyList())
                }
            everySuspend { contributors.searchContributors(any(), any()) } returns
                ContributorSearchResponse(contributors = emptyList(), isOfflineResult = true, tookMs = 0L)

            return BulkEditViewModel(
                bookIds = requestedIds ?: books.map { it.id.value },
                bookRepository = repo,
                applier = BulkEditApplier(edits, tags, moods),
                errorBus = ErrorBus(),
                seriesRepository = series,
                contributorRepository = contributors,
                genreRepository = genres,
                tagRepository = tags,
                moodRepository = moods,
            )
        }

        test("the preview counts only books that would actually change") {
            // One book already has the publisher, so it is not affected. Counting it would overstate
            // what Apply does, on an operation with no undo.
            val vm = rig(listOf(book("b1", publisher = PUBLISHER), book("b2")))
            runTest {
                vm.state.test {
                    advanceUntilIdle()
                    vm.setPublisher(PUBLISHER)
                    advanceUntilIdle()

                    val editing = vm.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                    editing.preview.single().affectedCount shouldBe 1
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a shared value is offered as context, and differing values are not") {
            val agreeing = rig(listOf(book("b1", publisher = PUBLISHER), book("b2", publisher = PUBLISHER)))
            val differing = rig(listOf(book("b1", publisher = PUBLISHER), book("b2", publisher = "Gollancz")))
            runTest {
                agreeing.state.test {
                    advanceUntilIdle()
                    val editing = agreeing.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                    withClue("all books agree, so the shared value can be shown as a placeholder") {
                        editing.sharedPublisher shouldBe PUBLISHER
                    }
                    cancelAndIgnoreRemainingEvents()
                }
                differing.state.test {
                    advanceUntilIdle()
                    val editing = differing.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                    withClue("the books disagree, so there is no value to offer") {
                        editing.sharedPublisher shouldBe null
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("clearing a field removes its instruction rather than writing an empty value") {
            val vm = rig(listOf(book("b1")))
            runTest {
                vm.state.test {
                    advanceUntilIdle()
                    vm.setPublisher(PUBLISHER)
                    advanceUntilIdle()
                    vm.state.value
                        .shouldBeInstanceOf<BulkEditUiState.Editing>()
                        .edits.size shouldBe 1

                    vm.setPublisher("")
                    advanceUntilIdle()

                    withClue("empty means untouched — it must never become SetPublisher(\"\")") {
                        vm.state.value
                            .shouldBeInstanceOf<BulkEditUiState.Editing>()
                            .edits
                            .shouldBeEmpty()
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("apply reports how many books changed") {
            val vm = rig(listOf(book("b1", publisher = PUBLISHER), book("b2")))
            runTest {
                vm.events.test {
                    vm.setPublisher(PUBLISHER)
                    advanceUntilIdle()
                    vm.apply()
                    advanceUntilIdle()

                    awaitItem().shouldBeInstanceOf<BulkEditEvent.Applied>().changedCount shouldBe 1
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a mid-batch failure reports how many books were already committed") {
            // Books are applied in title order, so b1 is committed before b2 fails. Those commits
            // stand — there is no rollback — and the count is the only thing that tells the user so.
            val vm = rig(listOf(book("b1"), book("b2")), failOn = BookId("b2"))
            runTest {
                vm.events.test {
                    vm.setPublisher(PUBLISHER)
                    advanceUntilIdle()
                    vm.apply()
                    advanceUntilIdle()

                    val failed = awaitItem().shouldBeInstanceOf<BulkEditEvent.Failed>()
                    withClue("the books committed before the failure are not undone") {
                        failed.appliedCount shouldBe 1
                    }
                    failed.error.shouldBeInstanceOf<BookError.InvalidInput>()
                    cancelAndIgnoreRemainingEvents()
                }
                vm.state.test {
                    advanceUntilIdle()
                    withClue("a failed run must not leave the form stuck behind a spinner") {
                        vm.state.value
                            .shouldBeInstanceOf<BulkEditUiState.Editing>()
                            .isApplying shouldBe false
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
        test("a book that could not be loaded is not counted as one Apply will change") {
            // The selection can shrink between the grid and this screen — a book deleted from
            // another device is the realistic way. bookCount must describe what Apply will touch,
            // while requestedCount remembers what the user actually selected, so the screen can own
            // up to the difference instead of quietly editing fewer books than were chosen.
            val vm = rig(listOf(book("b1")), requestedIds = listOf("b1", "gone"))
            runTest {
                vm.state.test {
                    awaitItem()
                    advanceUntilIdle()

                    val editing = vm.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                    editing.bookCount shouldBe 1
                    withClue("the user selected two, and the screen must be able to say so") {
                        editing.requestedCount shouldBe 2
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
        test("the number on Apply is the number Apply reports changing") {
            // The button must not say "Change 2 books" and then report "1 book updated". Both come
            // from the same planning function over the same books, so the promise the user reads
            // and the outcome they get cannot drift apart.
            val vm = rig(listOf(book("b1", publisher = PUBLISHER), book("b2")))
            runTest {
                vm.events.test {
                    vm.state.test {
                        awaitItem()
                        advanceUntilIdle()
                        vm.setPublisher(PUBLISHER)
                        advanceUntilIdle()

                        val editing = vm.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                        withClue("one of the two books already publishes with Tor") {
                            editing.changedBookCount shouldBe 1
                        }
                        editing.bookCount shouldBe 2
                        cancelAndIgnoreRemainingEvents()
                    }

                    vm.apply()
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<BulkEditEvent.Applied>().changedCount shouldBe 1
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
        test("a keystroke past the limit is refused, not thrown") {
            // The setters are driven by a text field, so every intermediate string reaches them.
            // BulkEdit validates eagerly — which is what keeps actionsFor total — so an unguarded
            // setter would turn the 201st character into a crash mid-typing. Refusing the change
            // leaves the last good value in the field, which is what a length-capped field does.
            val vm = rig(listOf(book("b1")))
            runTest {
                vm.state.test {
                    awaitItem()
                    advanceUntilIdle()
                    vm.setPublisher("Tor")
                    advanceUntilIdle()

                    vm.setPublisher("x".repeat(BookUpdate.MAX_PUBLISHER + 1))
                    vm.setLanguage("x".repeat(BookUpdate.MAX_LANGUAGE + 1))
                    vm.setYear(BookUpdate.MAX_YEAR + 1)
                    advanceUntilIdle()

                    val editing = vm.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                    withClue("the over-long publisher must not have replaced the good one") {
                        editing.edits
                            .filterIsInstance<BulkEdit.SetPublisher>()
                            .single()
                            .publisher shouldBe "Tor"
                    }
                    withClue("and nothing invalid may have been recorded") {
                        editing.edits.filterIsInstance<BulkEdit.SetLanguage>().shouldBeEmpty()
                        editing.edits.filterIsInstance<BulkEdit.SetPublishYear>().shouldBeEmpty()
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a genre the books already carry is not counted as a change") {
            // Add* unions, so an instruction naming what a book already has is a no-op for that book.
            // Counting it would overstate an operation with no undo — the same property the publisher
            // preview has, on the path where the union lives rather than a replacement.
            val fantasy = Genre(id = "g1", name = "Fantasy", slug = "fantasy", path = "/fantasy")
            val vm =
                rig(
                    listOf(
                        book("b1").copy(genres = listOf(fantasy)),
                        book("b2"),
                    ),
                )
            runTest {
                vm.state.test {
                    advanceUntilIdle()
                    vm.setGenres(listOf(BookGenreInput(genreId = GenreId(fantasy.id))))
                    advanceUntilIdle()

                    val editing = vm.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                    withClue("only the book without the genre changes") {
                        editing.preview.single().affectedCount shouldBe 1
                        editing.changedBookCount shouldBe 1
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("the pickers offer what the library already holds") {
            // A bulk edit adds existing things; the picker is where that constraint is felt.
            val vm =
                rig(
                    listOf(book("b1")),
                    libraryGenres = listOf(Genre(id = "g1", name = "Fantasy", slug = "fantasy", path = "/fantasy")),
                    libraryTags = listOf(Tag(id = "t1", name = "Found Family", slug = "found-family")),
                    libraryMoods = listOf(Mood(id = "m1", name = "Feel-Good", slug = "feel-good")),
                )
            runTest {
                // The catalogue starts empty and fills when the stream is collected, so the
                // assertion is on where it settles, not on its first frame.
                vm.genres.test {
                    advanceUntilIdle()
                    expectMostRecentItem().map { it.name } shouldBe listOf("Fantasy")
                    cancelAndIgnoreRemainingEvents()
                }
                vm.tags.test {
                    advanceUntilIdle()
                    expectMostRecentItem().map { it.name } shouldBe listOf("Found Family")
                    cancelAndIgnoreRemainingEvents()
                }
                vm.moods.test {
                    advanceUntilIdle()
                    expectMostRecentItem().map { it.name } shouldBe listOf("Feel-Good")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a series query too short to be a shortlist offers nothing") {
            val match = SeriesSearchResult(id = "s1", name = "The Stormlight Archive", bookCount = 5)
            val vm = rig(listOf(book("b1")), seriesMatches = listOf(match))
            runTest {
                vm.seriesMatches.test {
                    advanceUntilIdle()
                    awaitItem().shouldBeEmpty()

                    vm.setSeriesQuery("S")
                    advanceUntilIdle()
                    withClue("one letter matches most of a library, so it is not a shortlist") {
                        expectNoEvents()
                    }

                    vm.setSeriesQuery("Storm")
                    advanceUntilIdle()
                    awaitItem().map { it.name } shouldBe listOf("The Stormlight Archive")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("the form reads its chosen relations back out of the instructions") {
            // The form holds no second copy of what was picked, so the chips and the instruction
            // list cannot drift apart.
            val vm = rig(listOf(book("b1")))
            runTest {
                vm.state.test {
                    advanceUntilIdle()
                    vm.setSeries(BookSeriesInput(name = "The Stormlight Archive"))
                    vm.setGenres(listOf(BookGenreInput(genreId = GenreId("g1"))))
                    vm.setTags(listOf("Found Family"))
                    vm.setMoods(listOf("Feel-Good"))
                    advanceUntilIdle()

                    val editing = vm.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                    editing.seriesInput?.name shouldBe "The Stormlight Archive"
                    editing.genreInput.map { it.genreId.value } shouldBe listOf("g1")
                    editing.tagInput shouldBe listOf("Found Family")
                    editing.moodInput shouldBe listOf("Feel-Good")

                    withClue("and clearing a picker removes its instruction rather than writing a blank") {
                        vm.setTags(emptyList())
                        advanceUntilIdle()
                        vm.state.value
                            .shouldBeInstanceOf<BulkEditUiState.Editing>()
                            .tagInput
                            .shouldBeEmpty()
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
