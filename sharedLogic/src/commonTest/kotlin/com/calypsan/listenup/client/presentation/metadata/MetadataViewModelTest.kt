package com.calypsan.listenup.client.presentation.metadata

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.dto.MetadataSearchResults
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
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
                genres = emptyList(),
                coverUrl = "https://example.com/cover.jpg",
                coverUrlMaxSize = "https://example.com/cover-max.jpg",
            )

        fun buildVm(repo: MetadataRepository): MetadataViewModel = MetadataViewModel(metadataRepository = repo, errorBus = ErrorBus())

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
                everySuspend { repo.applyBookMetadata(any(), any(), any()) } returns AppResult.Success(Unit)
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

        test("applyMatch failure sets applyError and stays in Ready") {
            runTest {
                val book = makeBook()
                val repo = mock<MetadataRepository>()
                val error = TransportError.NetworkUnavailable()
                everySuspend { repo.getBookMetadata(any(), any()) } returns AppResult.Success(book)
                everySuspend { repo.applyBookMetadata(any(), any(), any()) } returns AppResult.Failure(error)
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

        // ── reset() ───────────────────────────────────────────────────────────

        test("reset returns to Idle preserving region") {
            runTest {
                val vm = buildVm(mock())
                vm.initForBook("b1", "Dune", "FH")
                vm.changeRegion(AudibleRegion.DE)
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
