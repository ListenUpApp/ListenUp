package com.calypsan.listenup.client.presentation.metadata

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapter
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.dto.MetadataSearchResults
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class MetadataViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun makeBook(
            asin: String = "B001",
            title: String = "Test Book",
            authorAsin: String? = "A1",
        ): MetadataBook =
            MetadataBook(
                asin = asin,
                title = title,
                subtitle = "Subtitle",
                description = "Description",
                publisher = "Publisher",
                releaseDate = "2024-01-01",
                runtimeMinutes = 120,
                language = "en",
                authors = listOf(MetadataContributorRef(asin = authorAsin, name = "Author One")),
                narrators = listOf(MetadataContributorRef(asin = "N1", name = "Narrator One")),
                series = emptyList(),
                genres = listOf("Fantasy", "Sci-Fi"),
                moods = listOf("Dark", "Hopeful"),
                tags = listOf("Found Family", "Slow Burn"),
                coverUrl = "https://example.com/cover.jpg",
                coverUrlMaxSize = "https://example.com/cover-max.jpg",
            )

        fun buildVm(
            repo: MetadataRepository,
            bookRepo: BookRepository = mock { everySuspend { getChapters(any()) } returns emptyList() },
            currentGenres: List<String> = emptyList(),
            currentMoods: List<String> = emptyList(),
            currentTags: List<String> = emptyList(),
        ): MetadataViewModel =
            MetadataViewModel(
                metadataRepository = repo,
                bookRepository = bookRepo,
                genreRepository =
                    mock {
                        everySuspend { getGenresForBook(any()) } returns
                            currentGenres.mapIndexed { i, name -> Genre(id = "g$i", name = name, slug = name, path = "/$name") }
                    },
                moodRepository =
                    mock {
                        every { observeMoodsForBook(any()) } returns
                            MutableStateFlow(currentMoods.mapIndexed { i, name -> Mood(id = "m$i", name = name, slug = name) })
                    },
                tagRepository =
                    mock {
                        every { observeTagsForBook(any()) } returns
                            MutableStateFlow(currentTags.mapIndexed { i, name -> Tag(id = "t$i", name = name, slug = name) })
                    },
                errorBus = ErrorBus(),
            )

        suspend fun TestScope.readyVmWithTwoChapters(): MetadataViewModel {
            val book = makeBook(asin = "B001", title = "Dune")
            val repo = mock<MetadataRepository>()
            everySuspend { repo.searchBooks(any(), any()) } returns AppResult.Success(MetadataSearchResults(listOf(book)))
            everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
            everySuspend { repo.getBookChapters(any(), any()) } returns
                AppResult.Success(MetadataChapters(listOf(MetadataChapter("Prologue", 0L, 1000L), MetadataChapter("Chapter One", 1000L, 1000L))))
            val bookRepo =
                mock<BookRepository> {
                    everySuspend { getChapters("b1") } returns
                        listOf(Chapter("c0", "Track 1", 1000L, 0L), Chapter("c1", "Track 2", 1000L, 1000L))
                }
            val vm = buildVm(repo, bookRepo)
            vm.initForBook("b1", "Dune", "Frank Herbert")
            vm.search()
            advanceUntilIdle()
            vm.selectMatch(book)
            advanceUntilIdle()
            return vm
        }

        // ── Initial state ──────────────────────────────────────────────────────

        test("initial state is Idle with US region") {
            runTest {
                val vm = buildVm(mock())
                val state = vm.state.value
                state.shouldBeInstanceOf<MetadataUiState.Idle>()
                state.region shouldBe AudibleRegion.US
            }
        }

        // ── initForBook ────────────────────────────────────────────────────────

        test("initForBook transitions to Search Idle with seeded query") {
            runTest {
                val vm = buildVm(mock())
                vm.initForBook(bookId = "b1", title = "Dune", author = "Frank Herbert")

                val state = vm.state.value.shouldBeInstanceOf<MetadataUiState.Search>()
                state.context.bookId shouldBe "b1"
                state.query shouldBe "Dune Frank Herbert"
                state.loadState shouldBe SearchLoadState.Idle
            }
        }

        // ── search() ──────────────────────────────────────────────────────────

        test("search success transitions to SearchLoadState.Loaded") {
            runTest {
                val book = makeBook()
                val repo = mock<MetadataRepository>()
                everySuspend { repo.searchBooks(any(), any()) } returns
                    AppResult.Success(MetadataSearchResults(listOf(book)))
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "Frank Herbert")
                vm.search()
                advanceUntilIdle()

                val state = vm.state.value.shouldBeInstanceOf<MetadataUiState.Search>()
                val loaded = state.loadState.shouldBeInstanceOf<SearchLoadState.Loaded>()
                loaded.results.size shouldBe 1
            }
        }

        test("search failure transitions to SearchLoadState.Failed and emits to ErrorBus") {
            runTest {
                val repo = mock<MetadataRepository>()
                val error = TransportError.NetworkUnavailable()
                everySuspend { repo.searchBooks(any(), any()) } returns AppResult.Failure(error)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "")
                vm.search()
                advanceUntilIdle()

                val state = vm.state.value.shouldBeInstanceOf<MetadataUiState.Search>()
                val failed = state.loadState.shouldBeInstanceOf<SearchLoadState.Failed>()
                failed.message shouldBe error.message
            }
        }

        test("search that never resolves surfaces Failed after the timeout, not an infinite spinner") {
            runTest {
                val repo = mock<MetadataRepository>()
                // Black-hole WebSocket: the RPC never returns and never throws.
                everySuspend { repo.searchBooks(any(), any()) } calls { awaitCancellation() }
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "Frank Herbert")
                vm.search()
                advanceUntilIdle()

                val state = vm.state.value.shouldBeInstanceOf<MetadataUiState.Search>()
                state.loadState.shouldBeInstanceOf<SearchLoadState.Failed>()
            }
        }

        test("search throwing an unexpected error surfaces Failed instead of hanging InFlight") {
            runTest {
                val repo = mock<MetadataRepository>()
                everySuspend { repo.searchBooks(any(), any()) } throws IllegalStateException("boom")
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "Frank Herbert")
                vm.search()
                advanceUntilIdle()

                val state = vm.state.value.shouldBeInstanceOf<MetadataUiState.Search>()
                state.loadState.shouldBeInstanceOf<SearchLoadState.Failed>()
            }
        }

        test("preview that never resolves surfaces Failed after the timeout, not an infinite spinner") {
            runTest {
                val book = makeBook(asin = "B001", title = "Dune")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } calls { awaitCancellation() }
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "Frank Herbert")
                vm.selectMatch(book)
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<MetadataUiState.Preview>()
                preview.loadState.shouldBeInstanceOf<PreviewLoadState.Failed>()
            }
        }

        // ── selectMatch() ─────────────────────────────────────────────────────

        test("selectMatch transitions to Preview.Ready when getBookMetadata succeeds") {
            runTest {
                val book = makeBook(asin = "B001", title = "Dune")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.searchBooks(any(), any()) } returns
                    AppResult.Success(MetadataSearchResults(listOf(book)))
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "Frank Herbert")
                vm.search()
                advanceUntilIdle()
                vm.selectMatch(book)
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<MetadataUiState.Preview>()
                val ready = preview.loadState.shouldBeInstanceOf<PreviewLoadState.Ready>()
                ready.preview.title shouldBe "Dune"
                ready.selections.cover shouldBe true
                ready.selections.selectedAuthors shouldBe setOf("A1")
            }
        }

        test("selectMatch null preview falls back to search result data") {
            runTest {
                val book = makeBook(title = "Fallback Title")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.searchBooks(any(), any()) } returns
                    AppResult.Success(MetadataSearchResults(listOf(book)))
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(null)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Fallback Title", "")
                vm.search()
                advanceUntilIdle()
                vm.selectMatch(book)
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<MetadataUiState.Preview>()
                val ready = preview.loadState.shouldBeInstanceOf<PreviewLoadState.Ready>()
                ready.preview.title shouldBe "Fallback Title"
                ready.previewNotFound shouldBe true
            }
        }

        test("selectMatch failure with non-blank title uses search result as fallback") {
            runTest {
                val book = makeBook(title = "Fallback Title")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } returns
                    AppResult.Failure(TransportError.NetworkUnavailable())
                val vm = buildVm(repo)

                vm.initForBook("b1", "Fallback Title", "")
                vm.selectMatch(book)
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<MetadataUiState.Preview>()
                val ready = preview.loadState.shouldBeInstanceOf<PreviewLoadState.Ready>()
                ready.preview.title shouldBe "Fallback Title"
            }
        }

        test("selectMatch failure with blank title transitions to PreviewLoadState.Failed") {
            runTest {
                val emptyBook =
                    makeBook(asin = "B001", title = "").copy(
                        authors = emptyList(),
                        narrators = emptyList(),
                    )
                val repo = mock<MetadataRepository>()
                val error = TransportError.NetworkUnavailable()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Failure(error)
                val vm = buildVm(repo)

                vm.initForBook("b1", "", "")
                vm.selectMatch(emptyBook)
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<MetadataUiState.Preview>()
                val failed = preview.loadState.shouldBeInstanceOf<PreviewLoadState.Failed>()
                failed.message shouldBe error.message
            }
        }

        // ── clearSelection() ──────────────────────────────────────────────────

        test("clearSelection returns to Search with results preserved") {
            runTest {
                val book = makeBook()
                val repo = mock<MetadataRepository>()
                everySuspend { repo.searchBooks(any(), any()) } returns
                    AppResult.Success(MetadataSearchResults(listOf(book)))
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "FH")
                vm.search()
                advanceUntilIdle()
                vm.selectMatch(book)
                advanceUntilIdle()

                vm.clearSelection()

                val state = vm.state.value.shouldBeInstanceOf<MetadataUiState.Search>()
                val loaded = state.loadState.shouldBeInstanceOf<SearchLoadState.Loaded>()
                loaded.results.size shouldBe 1
            }
        }

        // ── toggleField() ─────────────────────────────────────────────────────

        test("toggleField flips the corresponding selection on Ready") {
            runTest {
                val book = makeBook()
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "FH")
                vm.selectMatch(book)
                advanceUntilIdle()

                vm.toggleField(MetadataField.TITLE)

                val preview = vm.state.value.shouldBeInstanceOf<MetadataUiState.Preview>()
                val ready = preview.loadState.shouldBeInstanceOf<PreviewLoadState.Ready>()
                // Initially true (title has data), toggle flips it.
                ready.selections.title shouldBe false
            }
        }

        // ── toggleAuthor() ────────────────────────────────────────────────────

        test("toggleAuthor adds and removes ASIN from selected set") {
            runTest {
                val book = makeBook(authorAsin = "A1")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "FH")
                vm.selectMatch(book)
                advanceUntilIdle()

                // A1 was initialized as selected; remove it
                vm.toggleAuthor("A1")
                val afterRemove =
                    (
                        vm.state.value
                            .shouldBeInstanceOf<MetadataUiState.Preview>()
                            .loadState
                            .shouldBeInstanceOf<PreviewLoadState.Ready>()
                    )
                afterRemove.selections.selectedAuthors shouldBe emptySet()

                // Add A2
                vm.toggleAuthor("A2")
                val afterAdd =
                    (
                        vm.state.value
                            .shouldBeInstanceOf<MetadataUiState.Preview>()
                            .loadState
                            .shouldBeInstanceOf<PreviewLoadState.Ready>()
                    )
                afterAdd.selections.selectedAuthors shouldBe setOf("A2")
            }
        }

        // ── applyMatch() ──────────────────────────────────────────────────────

        test("applyMatch success emits MatchApplied event") {
            runTest {
                val book = makeBook()
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                everySuspend { repo.applyBookMetadata(any(), any(), any(), any()) } returns AppResult.Success(Unit)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "FH")
                vm.selectMatch(book)
                advanceUntilIdle()

                vm.events.test {
                    vm.applyMatch()
                    advanceUntilIdle()
                    awaitItem() shouldBe MetadataEvent.MatchApplied
                }

                val ready =
                    vm.state.value
                        .shouldBeInstanceOf<MetadataUiState.Preview>()
                        .loadState
                        .shouldBeInstanceOf<PreviewLoadState.Ready>()
                ready.isApplying shouldBe false
                ready.applyError shouldBe null
            }
        }

        test("applyMatch forwards toggled MetadataApplySelection to repository") {
            runTest {
                val book = makeBook()
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                everySuspend { repo.applyBookMetadata(any(), any(), any(), any()) } returns AppResult.Success(Unit)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "FH")
                vm.selectMatch(book)
                advanceUntilIdle()

                // Deselect author A1 and toggle TITLE off — mapper must reflect both changes
                vm.toggleAuthor("A1")
                vm.toggleField(MetadataField.TITLE)
                vm.applyMatch()
                advanceUntilIdle()

                verifySuspend {
                    repo.applyBookMetadata(
                        BookId("b1"),
                        "B001",
                        AudibleRegion.US,
                        MetadataApplySelection(
                            title = false,
                            subtitle = true,
                            description = true,
                            publisher = true,
                            releaseDate = true,
                            language = true,
                            cover = true,
                            authorAsins = emptySet(),
                            narratorAsins = setOf("N1"),
                            seriesAsins = emptySet(),
                            genres = setOf("Fantasy", "Sci-Fi"),
                            moods = setOf("Dark", "Hopeful"),
                            tags = setOf("Found Family", "Slow Burn"),
                        ),
                    )
                }
            }
        }

        test("applyMatch forwards the chosen cover URL as MetadataApplySelection.coverUrl") {
            runTest {
                val book = makeBook()
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                everySuspend { repo.applyBookMetadata(any(), any(), any(), any()) } returns AppResult.Success(Unit)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "FH")
                vm.selectMatch(book)
                advanceUntilIdle()

                vm.selectCover("https://itunes/hd.jpg")
                vm.applyMatch()
                advanceUntilIdle()

                verifySuspend {
                    repo.applyBookMetadata(
                        BookId("b1"),
                        "B001",
                        AudibleRegion.US,
                        MetadataApplySelection(
                            title = true,
                            subtitle = true,
                            description = true,
                            publisher = true,
                            releaseDate = true,
                            language = true,
                            cover = true,
                            authorAsins = setOf("A1"),
                            narratorAsins = setOf("N1"),
                            seriesAsins = emptySet(),
                            coverUrl = "https://itunes/hd.jpg",
                            genres = setOf("Fantasy", "Sci-Fi"),
                            moods = setOf("Dark", "Hopeful"),
                            tags = setOf("Found Family", "Slow Burn"),
                        ),
                    )
                }
            }
        }

        test("applyMatch forwards selected genres; initializeSelections seeds them from the preview") {
            runTest {
                val book = makeBook()
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                everySuspend { repo.applyBookMetadata(any(), any(), any(), any()) } returns AppResult.Success(Unit)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "FH")
                vm.selectMatch(book)
                advanceUntilIdle()

                vm.toggleGenre("Sci-Fi") // turn one off
                vm.applyMatch()
                advanceUntilIdle()

                verifySuspend {
                    repo.applyBookMetadata(
                        BookId("b1"),
                        "B001",
                        AudibleRegion.US,
                        MetadataApplySelection(
                            title = true,
                            subtitle = true,
                            description = true,
                            publisher = true,
                            releaseDate = true,
                            language = true,
                            cover = true,
                            authorAsins = setOf("A1"),
                            narratorAsins = setOf("N1"),
                            seriesAsins = emptySet(),
                            coverUrl = null,
                            genres = setOf("Fantasy"),
                            moods = setOf("Dark", "Hopeful"),
                            tags = setOf("Found Family", "Slow Burn"),
                        ),
                    )
                }
            }
        }

        test("applyMatch forwards selected moods + tags; initializeSelections seeds them from the preview") {
            runTest {
                val book = makeBook()
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                everySuspend { repo.applyBookMetadata(any(), any(), any(), any()) } returns AppResult.Success(Unit)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "FH")
                vm.selectMatch(book)
                advanceUntilIdle()

                vm.toggleMood("Hopeful") // turn one mood off
                vm.toggleTag("Slow Burn") // turn one tag off
                vm.applyMatch()
                advanceUntilIdle()

                verifySuspend {
                    repo.applyBookMetadata(
                        BookId("b1"),
                        "B001",
                        AudibleRegion.US,
                        MetadataApplySelection(
                            title = true,
                            subtitle = true,
                            description = true,
                            publisher = true,
                            releaseDate = true,
                            language = true,
                            cover = true,
                            authorAsins = setOf("A1"),
                            narratorAsins = setOf("N1"),
                            seriesAsins = emptySet(),
                            coverUrl = null,
                            genres = setOf("Fantasy", "Sci-Fi"),
                            moods = setOf("Dark"),
                            tags = setOf("Found Family"),
                        ),
                    )
                }
            }
        }

        test("applyMatch failure sets applyError and stays in Ready") {
            runTest {
                val book = makeBook()
                val repo = mock<MetadataRepository>()
                val error = TransportError.NetworkUnavailable()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                everySuspend { repo.applyBookMetadata(any(), any(), any(), any()) } returns AppResult.Failure(error)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "FH")
                vm.selectMatch(book)
                advanceUntilIdle()

                vm.applyMatch()
                advanceUntilIdle()

                val ready =
                    vm.state.value
                        .shouldBeInstanceOf<MetadataUiState.Preview>()
                        .loadState
                        .shouldBeInstanceOf<PreviewLoadState.Ready>()
                ready.isApplying shouldBe false
                ready.applyError shouldBe error.message
            }
        }

        // ── current + proposed candidate union ────────────────────────────────

        test("preview candidate lists union the book's current moods with the match's proposed, seeded all-on") {
            runTest {
                val book =
                    makeBook(asin = "B001", title = "Dune").copy(
                        genres = listOf("Fantasy"),
                        moods = listOf("Cozy", "Tense"),
                        tags = listOf("Slow Burn"),
                    )
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                val vm =
                    buildVm(
                        repo,
                        currentGenres = listOf("Sci-Fi"),
                        currentMoods = listOf("Cozy"),
                        currentTags = listOf("Found Family"),
                    )

                vm.initForBook("b1", "Dune", "Frank Herbert")
                vm.selectMatch(book)
                advanceUntilIdle()

                val ready =
                    vm.state.value
                        .shouldBeInstanceOf<MetadataUiState.Preview>()
                        .loadState
                        .shouldBeInstanceOf<PreviewLoadState.Ready>()

                // current-first, deduped
                ready.moodCandidates shouldBe listOf("Cozy", "Tense")
                ready.genreCandidates shouldBe listOf("Sci-Fi", "Fantasy")
                ready.tagCandidates shouldBe listOf("Found Family", "Slow Burn")

                // all-on
                ready.selections.selectedMoods shouldBe setOf("Cozy", "Tense")
                ready.selections.selectedGenres shouldBe setOf("Sci-Fi", "Fantasy")
                ready.selections.selectedTags shouldBe setOf("Found Family", "Slow Burn")
            }
        }

        test("a current item with no proposed counterpart still appears and is selected (preserved on apply)") {
            runTest {
                val book =
                    makeBook(asin = "B001", title = "Dune").copy(
                        genres = emptyList(),
                        moods = emptyList(),
                        tags = emptyList(),
                    )
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                val vm = buildVm(repo, currentMoods = listOf("Cozy"))

                vm.initForBook("b1", "Dune", "Frank Herbert")
                vm.selectMatch(book)
                advanceUntilIdle()

                val ready =
                    vm.state.value
                        .shouldBeInstanceOf<MetadataUiState.Preview>()
                        .loadState
                        .shouldBeInstanceOf<PreviewLoadState.Ready>()

                ready.moodCandidates shouldBe listOf("Cozy")
                ready.selections.selectedMoods shouldBe setOf("Cozy")
            }
        }

        // ── chapter-name suggestion ───────────────────────────────────────────

        test("matching chapter counts produce ChapterSuggestion.Available with current→suggested rows") {
            runTest {
                val book = makeBook(asin = "B001", title = "Dune")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.searchBooks(any(), any()) } returns AppResult.Success(MetadataSearchResults(listOf(book)))
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                everySuspend { repo.getBookChapters(any(), any()) } returns
                    AppResult.Success(
                        MetadataChapters(
                            listOf(
                                MetadataChapter(title = "Prologue", startMs = 0L, lengthMs = 1000L),
                                MetadataChapter(title = "Chapter One", startMs = 1000L, lengthMs = 1000L),
                            ),
                        ),
                    )
                val bookRepo =
                    mock<BookRepository> {
                        everySuspend { getChapters("b1") } returns
                            listOf(
                                Chapter(id = "c0", title = "Track 1", duration = 1000L, startTime = 0L),
                                Chapter(id = "c1", title = "Track 2", duration = 1000L, startTime = 1000L),
                            )
                    }
                val vm = buildVm(repo, bookRepo)

                vm.initForBook("b1", "Dune", "Frank Herbert")
                vm.search()
                advanceUntilIdle()
                vm.selectMatch(book)
                advanceUntilIdle()

                val ready =
                    (vm.state.value as MetadataUiState.Preview).loadState as PreviewLoadState.Ready
                val available = ready.chapterSuggestion.shouldBeInstanceOf<ChapterSuggestion.Available>()
                available.rows.map { it.currentName } shouldBe listOf("Track 1", "Track 2")
                available.rows.map { it.suggestedName } shouldBe listOf("Prologue", "Chapter One")
                available.selectedOrdinals shouldBe setOf(0, 1)
            }
        }

        test("differing chapter counts produce ChapterSuggestion.CountMismatch") {
            runTest {
                val book = makeBook(asin = "B001", title = "Dune")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.searchBooks(any(), any()) } returns AppResult.Success(MetadataSearchResults(listOf(book)))
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                everySuspend { repo.getBookChapters(any(), any()) } returns
                    AppResult.Success(MetadataChapters((0 until 5).map { MetadataChapter("C$it", it * 1000L, 1000L) }))
                val bookRepo =
                    mock<BookRepository> {
                        everySuspend { getChapters("b1") } returns
                            listOf(Chapter("c0", "Track 1", 1000L, 0L), Chapter("c1", "Track 2", 1000L, 1000L))
                    }
                val vm = buildVm(repo, bookRepo)

                vm.initForBook("b1", "Dune", "Frank Herbert")
                vm.search()
                advanceUntilIdle()
                vm.selectMatch(book)
                advanceUntilIdle()

                val ready = (vm.state.value as MetadataUiState.Preview).loadState as PreviewLoadState.Ready
                val mismatch = ready.chapterSuggestion.shouldBeInstanceOf<ChapterSuggestion.CountMismatch>()
                mismatch.localCount shouldBe 2
                mismatch.audibleCount shouldBe 5
            }
        }

        test("no local chapters produce ChapterSuggestion.Unavailable") {
            runTest {
                val book = makeBook(asin = "B001", title = "Dune")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.searchBooks(any(), any()) } returns AppResult.Success(MetadataSearchResults(listOf(book)))
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                everySuspend { repo.getBookChapters(any(), any()) } returns
                    AppResult.Success(MetadataChapters(listOf(MetadataChapter("Prologue", 0L, 1000L))))
                val bookRepo = mock<BookRepository> { everySuspend { getChapters("b1") } returns emptyList() }
                val vm = buildVm(repo, bookRepo)
                vm.initForBook("b1", "Dune", "Frank Herbert")
                vm.search()
                advanceUntilIdle()
                vm.selectMatch(book)
                advanceUntilIdle()
                val ready = (vm.state.value as MetadataUiState.Preview).loadState as PreviewLoadState.Ready
                ready.chapterSuggestion shouldBe ChapterSuggestion.Unavailable
            }
        }

        test("Audible chapter fetch failure produces ChapterSuggestion.Unavailable") {
            runTest {
                val book = makeBook(asin = "B001", title = "Dune")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.searchBooks(any(), any()) } returns AppResult.Success(MetadataSearchResults(listOf(book)))
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                everySuspend { repo.getBookChapters(any(), any()) } returns AppResult.Failure(MetadataError.ExternalUnavailable())
                val bookRepo =
                    mock<BookRepository> {
                        everySuspend { getChapters("b1") } returns listOf(Chapter("c0", "Track 1", 1000L, 0L))
                    }
                val vm = buildVm(repo, bookRepo)
                vm.initForBook("b1", "Dune", "Frank Herbert")
                vm.search()
                advanceUntilIdle()
                vm.selectMatch(book)
                advanceUntilIdle()
                val ready = (vm.state.value as MetadataUiState.Preview).loadState as PreviewLoadState.Ready
                ready.chapterSuggestion shouldBe ChapterSuggestion.Unavailable
            }
        }

        test("applyChapterNames failure sets applyError and clears isApplying") {
            runTest {
                val book = makeBook(asin = "B001", title = "Dune")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.searchBooks(any(), any()) } returns AppResult.Success(MetadataSearchResults(listOf(book)))
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                everySuspend { repo.getBookChapters(any(), any()) } returns
                    AppResult.Success(MetadataChapters(listOf(MetadataChapter("Prologue", 0L, 1000L), MetadataChapter("Chapter One", 1000L, 1000L))))
                everySuspend { repo.applyChapterNames(any(), any(), any(), any()) } returns AppResult.Failure(MetadataError.ChapterCountMismatch())
                val bookRepo =
                    mock<BookRepository> {
                        everySuspend { getChapters("b1") } returns listOf(Chapter("c0", "Track 1", 1000L, 0L), Chapter("c1", "Track 2", 1000L, 1000L))
                    }
                val vm = buildVm(repo, bookRepo)
                vm.initForBook("b1", "Dune", "Frank Herbert")
                vm.search()
                advanceUntilIdle()
                vm.selectMatch(book)
                advanceUntilIdle()
                vm.applyChapterNames()
                advanceUntilIdle()
                val available =
                    ((vm.state.value as MetadataUiState.Preview).loadState as PreviewLoadState.Ready)
                        .chapterSuggestion
                        .shouldBeInstanceOf<ChapterSuggestion.Available>()
                available.isApplying shouldBe false
                available.applyError shouldBe MetadataError.ChapterCountMismatch().message
            }
        }

        test("toggleChapter removes then re-adds an ordinal from the selection") {
            runTest {
                val vm = readyVmWithTwoChapters()
                vm.toggleChapter(1)
                ((vm.state.value as MetadataUiState.Preview).loadState as PreviewLoadState.Ready)
                    .chapterSuggestion
                    .shouldBeInstanceOf<ChapterSuggestion.Available>()
                    .selectedOrdinals shouldBe setOf(0)
                vm.toggleChapter(1)
                ((vm.state.value as MetadataUiState.Preview).loadState as PreviewLoadState.Ready)
                    .chapterSuggestion
                    .shouldBeInstanceOf<ChapterSuggestion.Available>()
                    .selectedOrdinals shouldBe setOf(0, 1)
            }
        }

        test("applyChapterNames success emits ChapterNamesApplied and calls repo with selected ordinals") {
            runTest {
                val book = makeBook(asin = "B001", title = "Dune")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.searchBooks(any(), any()) } returns AppResult.Success(MetadataSearchResults(listOf(book)))
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                everySuspend { repo.getBookChapters(any(), any()) } returns
                    AppResult.Success(MetadataChapters(listOf(MetadataChapter("Prologue", 0L, 1000L), MetadataChapter("Chapter One", 1000L, 1000L))))
                everySuspend { repo.applyChapterNames(any(), any(), any(), any()) } returns AppResult.Success(Unit)
                val bookRepo =
                    mock<BookRepository> {
                        everySuspend { getChapters("b1") } returns
                            listOf(Chapter("c0", "Track 1", 1000L, 0L), Chapter("c1", "Track 2", 1000L, 1000L))
                    }
                val vm = buildVm(repo, bookRepo)
                vm.initForBook("b1", "Dune", "Frank Herbert")
                vm.search()
                advanceUntilIdle()
                vm.selectMatch(book)
                advanceUntilIdle()
                vm.toggleChapter(1)

                vm.events.test {
                    vm.applyChapterNames()
                    advanceUntilIdle()
                    awaitItem() shouldBe MetadataEvent.ChapterNamesApplied
                }
                verifySuspend { repo.applyChapterNames(BookId("b1"), "B001", AudibleRegion.US, setOf(0)) }
            }
        }

        // ── changeRegion() ────────────────────────────────────────────────────

        test("changeRegion in Preview phase refetches with new region") {
            runTest {
                val usBook = makeBook(title = "US Edition")
                val ukBook = makeBook(title = "UK Edition")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), AudibleRegion.US) } returns AppResult.Success(usBook)
                everySuspend { repo.getBookMetadata(any(), AudibleRegion.UK) } returns AppResult.Success(ukBook)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "FH")
                vm.selectMatch(usBook)
                advanceUntilIdle()

                vm.changeRegion(AudibleRegion.UK)
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<MetadataUiState.Preview>()
                preview.region shouldBe AudibleRegion.UK
                preview.loadState
                    .shouldBeInstanceOf<PreviewLoadState.Ready>()
                    .preview.title shouldBe "UK Edition"
            }
        }

        test("changeRegion in Search phase re-runs the search with the new region") {
            runTest {
                val repo = mock<MetadataRepository>()
                everySuspend { repo.searchBooks(any(), AudibleRegion.US) } returns
                    AppResult.Success(MetadataSearchResults(listOf(makeBook(title = "US Edition"))))
                everySuspend { repo.searchBooks(any(), AudibleRegion.CA) } returns
                    AppResult.Success(MetadataSearchResults(listOf(makeBook(title = "CA Edition"))))
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "FH")
                vm.search()
                advanceUntilIdle()

                vm.changeRegion(AudibleRegion.CA)
                advanceUntilIdle()

                val state = vm.state.value.shouldBeInstanceOf<MetadataUiState.Search>()
                state.region shouldBe AudibleRegion.CA
                state.loadState
                    .shouldBeInstanceOf<SearchLoadState.Loaded>()
                    .results
                    .single()
                    .title shouldBe "CA Edition"
                verifySuspend { repo.searchBooks(any(), AudibleRegion.CA) }
            }
        }

        test("region applied before selectMatch is used for the preview fetch") {
            runTest {
                val book = makeBook(asin = "B007")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                val vm = buildVm(repo)

                // Mirrors MatchPreviewRoute on a fresh per-entry VM: init, apply the region carried
                // across navigation, then select the match. (changeRegion's search is a blank no-op.)
                vm.initForBook(bookId = "b1", title = "", author = "")
                vm.changeRegion(AudibleRegion.CA)
                advanceUntilIdle()
                vm.selectMatch(book)
                advanceUntilIdle()

                vm.state.value
                    .shouldBeInstanceOf<MetadataUiState.Preview>()
                    .region shouldBe AudibleRegion.CA
                verifySuspend { repo.getBookMetadata("B007", AudibleRegion.CA) }
            }
        }

        // ── reset() ───────────────────────────────────────────────────────────

        test("reset returns to Idle preserving region") {
            runTest {
                // changeRegion in Search now re-runs the search, so the repo must stub searchBooks.
                val repo =
                    mock<MetadataRepository> {
                        everySuspend { searchBooks(any(), any()) } returns
                            AppResult.Success(MetadataSearchResults(emptyList()))
                    }
                val vm = buildVm(repo)
                vm.initForBook("b1", "Dune", "FH")
                vm.changeRegion(AudibleRegion.DE)
                advanceUntilIdle()
                vm.reset()

                val state = vm.state.value.shouldBeInstanceOf<MetadataUiState.Idle>()
                state.region shouldBe AudibleRegion.DE
            }
        }

        // ── buildCoverEntries (via Ready.coverEntries) ────────────────────────

        test("coverEntries includes iTunes HD and Audible options from preview") {
            runTest {
                val book =
                    makeBook().copy(
                        coverUrl = "https://audible.com/cover.jpg",
                        coverUrlMaxSize = "https://itunes.com/cover-max.jpg",
                    )
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "FH")
                vm.selectMatch(book)
                advanceUntilIdle()

                val ready =
                    vm.state.value
                        .shouldBeInstanceOf<MetadataUiState.Preview>()
                        .loadState
                        .shouldBeInstanceOf<PreviewLoadState.Ready>()
                ready.coverEntries.size shouldBe 2
                ready.coverEntries.any { it.label == "iTunes HD" } shouldBe true
                ready.coverEntries.any { it.label == "Audible" } shouldBe true
            }
        }

        test("coverEntries has only Audible option when coverUrlMaxSize is null") {
            runTest {
                val book = makeBook().copy(coverUrlMaxSize = null)
                val repo = mock<MetadataRepository>()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                val vm = buildVm(repo)

                vm.initForBook("b1", "Dune", "FH")
                vm.selectMatch(book)
                advanceUntilIdle()

                val ready =
                    vm.state.value
                        .shouldBeInstanceOf<MetadataUiState.Preview>()
                        .loadState
                        .shouldBeInstanceOf<PreviewLoadState.Ready>()
                ready.coverEntries.size shouldBe 1
                ready.coverEntries.single().label shouldBe "Audible"
            }
        }
    })
