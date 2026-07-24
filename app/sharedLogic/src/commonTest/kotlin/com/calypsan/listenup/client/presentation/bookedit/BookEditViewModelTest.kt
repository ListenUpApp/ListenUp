package com.calypsan.listenup.client.presentation.bookedit

import com.calypsan.listenup.api.result.AppResult
import app.cash.turbine.test
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.api.result.failureOf
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.client.domain.model.BookEditData
import com.calypsan.listenup.client.domain.model.BookMetadata
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.ContributorSearchResponse
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.model.SeriesSearchResponse
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.book.LoadBookForEditUseCase
import com.calypsan.listenup.client.domain.usecase.book.UpdateBookUseCase
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for BookEditViewModel.
 *
 * Tests cover:
 * - Initial state (loading)
 * - Load book success/not found (via use case)
 * - Metadata change tracking (hasChanges)
 * - Contributor management (add, select, remove)
 * - Series management
 * - Genre/Tag management
 * - Save functionality (via use case)
 *
 * Uses Mokkery for mocking use cases and repository contracts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookEditViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixtures ==========

        class TestFixture {
            val loadBookForEditUseCase: LoadBookForEditUseCase = mock()
            val updateBookUseCase: UpdateBookUseCase = mock()
            val contributorRepository: ContributorRepository = mock()
            val seriesRepository: SeriesRepository = mock()
            val collectionRepository: CollectionRepository = mock()
            val bookEditRepository: BookEditRepository = mock()
            val userRepository: UserRepository = mock()
            val imageStagingRepository: ImageStagingRepository = mock()
            val errorBus: ErrorBus = ErrorBus()

            fun build(): BookEditViewModel =
                BookEditViewModel(
                    loadBookForEditUseCase = loadBookForEditUseCase,
                    updateBookUseCase = updateBookUseCase,
                    contributorRepository = contributorRepository,
                    seriesRepository = seriesRepository,
                    collectionRepository = collectionRepository,
                    bookEditRepository = bookEditRepository,
                    userRepository = userRepository,
                    imageStagingRepository = imageStagingRepository,
                    errorBus = errorBus,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()

            // Default stubs for delegate operations
            everySuspend { fixture.contributorRepository.searchContributors(any(), any()) } returns
                ContributorSearchResponse(emptyList(), false, 0L)
            everySuspend { fixture.seriesRepository.searchSeries(any(), any()) } returns
                SeriesSearchResponse(emptyList(), false, 0L)
            every { fixture.collectionRepository.observeCollections() } returns flowOf(emptyList())
            every { fixture.collectionRepository.observeBookCollectionIds(any()) } returns flowOf(emptyList())
            every { fixture.userRepository.observeIsAdmin() } returns flowOf(false)

            return fixture
        }

        fun createBookEditData(
            bookId: String = "book-1",
            title: String = "Test Book",
            subtitle: String = "",
            description: String = "",
            publishYear: String = "",
            publisher: String = "",
            language: String? = null,
            isbn: String = "",
            asin: String = "",
            abridged: Boolean = false,
            addedAt: Long? = null,
            contributors: List<EditableContributor> = emptyList(),
            series: List<EditableSeries> = emptyList(),
            genres: List<EditableGenre> = emptyList(),
            tags: List<EditableTag> = emptyList(),
            moods: List<EditableMood> = emptyList(),
            allGenres: List<EditableGenre> = emptyList(),
            allTags: List<EditableTag> = emptyList(),
            allMoods: List<EditableMood> = emptyList(),
            coverPath: String? = null,
        ): BookEditData =
            BookEditData(
                bookId = bookId,
                metadata =
                    BookMetadata(
                        title = title,
                        sortTitle = "",
                        subtitle = subtitle,
                        description = description,
                        publishYear = publishYear,
                        publisher = publisher,
                        language = language,
                        isbn = isbn,
                        asin = asin,
                        abridged = abridged,
                        addedAt = addedAt,
                    ),
                contributors = contributors,
                series = series,
                genres = genres,
                tags = tags,
                moods = moods,
                allGenres = allGenres,
                allTags = allTags,
                allMoods = allMoods,
                coverPath = coverPath,
            )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Initial State Tests ==========

        test("initial state has isLoading true") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build()

                // Then
                (viewModel.state.value.isLoading) shouldBe true
                viewModel.state.value.title shouldBe ""
                (viewModel.state.value.hasChanges) shouldBe false
            }
        }

        // ========== Load Book Tests ==========

        test("loadBook success populates state with book data") {
            runTest {
                // Given
                val fixture = createFixture()
                val author = EditableContributor(id = "author-1", name = "Brandon Sanderson", roles = setOf(ContributorRole.AUTHOR))
                val narrator = EditableContributor(id = "narrator-1", name = "Michael Kramer", roles = setOf(ContributorRole.NARRATOR))
                val editData =
                    createBookEditData(
                        bookId = "book-1",
                        title = "The Way of Kings",
                        subtitle = "Book One of the Stormlight Archive",
                        description = "An epic fantasy adventure",
                        publishYear = "2010",
                        publisher = "Tor Books",
                        language = "en",
                        contributors = listOf(author, narrator),
                    )
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()

                // When
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // Then
                val state = viewModel.state.value
                (state.isLoading) shouldBe false
                state.title shouldBe "The Way of Kings"
                state.subtitle shouldBe "Book One of the Stormlight Archive"
                state.description shouldBe "An epic fantasy adventure"
                state.publishYear shouldBe "2010"
                state.publisher shouldBe "Tor Books"
                state.language shouldBe "en"
                (state.hasChanges) shouldBe false
                state.error shouldBe null
            }
        }

        test("loadBook not found sets error state") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.loadBookForEditUseCase("nonexistent") } returns failureOf("Book not found")
                val viewModel = fixture.build()

                // When
                viewModel.loadBook("nonexistent")
                advanceUntilIdle()

                // Then
                val state = viewModel.state.value
                (state.isLoading) shouldBe false
                state.error shouldBe "Book not found"
            }
        }

        test("loadBook converts contributors to editable format with roles") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor =
                    EditableContributor(
                        id = "person-1",
                        name = "Patrick Rothfuss",
                        roles = setOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR),
                    )
                val editData =
                    createBookEditData(
                        bookId = "book-1",
                        title = "The Name of the Wind",
                        contributors = listOf(contributor),
                    )
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()

                // When
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // Then
                val state = viewModel.state.value
                state.contributors.size shouldBe 1
                val editable = state.contributors.first()
                editable.name shouldBe "Patrick Rothfuss"
                (ContributorRole.AUTHOR in editable.roles) shouldBe true
                (ContributorRole.NARRATOR in editable.roles) shouldBe true
            }
        }

        // ========== Metadata Change Tracking Tests ==========

        test("changing title sets hasChanges to true") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1", title = "Original Title")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()
                (viewModel.state.value.hasChanges) shouldBe false

                // When
                viewModel.onEvent(BookEditUiEvent.TitleChanged("New Title"))

                // Then
                (viewModel.state.value.hasChanges) shouldBe true
                viewModel.state.value.title shouldBe "New Title"
            }
        }

        test("reverting title to original clears hasChanges") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1", title = "Original Title")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When - change then revert
                viewModel.onEvent(BookEditUiEvent.TitleChanged("New Title"))
                (viewModel.state.value.hasChanges) shouldBe true
                viewModel.onEvent(BookEditUiEvent.TitleChanged("Original Title"))

                // Then
                (viewModel.state.value.hasChanges) shouldBe false
            }
        }

        test("publish year only accepts numeric input") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When - try to input invalid characters
                viewModel.onEvent(BookEditUiEvent.PublishYearChanged("20ab24cd"))

                // Then - only digits kept, max 4 chars
                viewModel.state.value.publishYear shouldBe "2024"
            }
        }

        // ========== Contributor Management Tests ==========

        test("adding role section makes role visible") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When
                viewModel.onEvent(BookEditUiEvent.AddRoleSection(ContributorRole.EDITOR))

                // Then
                (ContributorRole.EDITOR in viewModel.state.value.visibleRoles) shouldBe true
            }
        }

        test("entering contributor name adds new contributor with role") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1", contributors = emptyList())
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When
                viewModel.onEvent(BookEditUiEvent.RoleContributorEntered(ContributorRole.AUTHOR, "New Author"))
                advanceUntilIdle()

                // Then
                val authors = viewModel.state.value.authors
                authors.size shouldBe 1
                authors.first().name shouldBe "New Author"
                authors.first().id shouldBe null // New contributor has no ID
                (viewModel.state.value.hasChanges) shouldBe true
            }
        }

        test("selecting contributor from search adds with existing ID") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1", contributors = emptyList())
                val searchResult = ContributorSearchResult(id = "existing-1", name = "Existing Author", bookCount = 10)
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When
                viewModel.onEvent(BookEditUiEvent.RoleContributorSelected(ContributorRole.AUTHOR, searchResult))
                advanceUntilIdle()

                // Then
                val authors = viewModel.state.value.authors
                authors.size shouldBe 1
                authors.first().id shouldBe "existing-1"
                authors.first().name shouldBe "Existing Author"
            }
        }

        test("removing contributor from role updates state") {
            runTest {
                // Given
                val fixture = createFixture()
                val author = EditableContributor(id = "author-1", name = "Author Name", roles = setOf(ContributorRole.AUTHOR))
                val editData = createBookEditData(bookId = "book-1", contributors = listOf(author))
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()
                viewModel.state.value.authors.size shouldBe 1

                // When
                val editable =
                    viewModel.state.value.contributors
                        .first()
                viewModel.onEvent(BookEditUiEvent.RemoveContributor(editable, ContributorRole.AUTHOR))

                // Then
                viewModel.state.value.authors.size shouldBe 0
                (viewModel.state.value.hasChanges) shouldBe true
            }
        }

        // ========== Series Management Tests ==========

        test("selecting series from search adds to book") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1")
                val searchResult = SeriesSearchResult(id = "series-1", name = "The Stormlight Archive", bookCount = 4)
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When
                viewModel.onEvent(BookEditUiEvent.SeriesSelected(searchResult))

                // Then
                viewModel.state.value.series.size shouldBe 1
                viewModel.state.value.series
                    .first()
                    .name shouldBe "The Stormlight Archive"
                (viewModel.state.value.hasChanges) shouldBe true
            }
        }

        test("entering series name creates new series") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When
                viewModel.onEvent(BookEditUiEvent.SeriesEntered("New Fantasy Series"))

                // Then
                viewModel.state.value.series.size shouldBe 1
                viewModel.state.value.series
                    .first()
                    .name shouldBe "New Fantasy Series"
                viewModel.state.value.series
                    .first()
                    .id shouldBe null // New series has no ID
            }
        }

        test("updating series sequence modifies existing series") {
            runTest {
                // Given
                val fixture = createFixture()
                val series = EditableSeries(id = "series-1", name = "Mistborn", sequence = null)
                val editData = createBookEditData(bookId = "book-1", series = listOf(series))
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When
                val loadedSeries =
                    viewModel.state.value.series
                        .first()
                viewModel.onEvent(BookEditUiEvent.SeriesSequenceChanged(loadedSeries, "1"))

                // Then
                viewModel.state.value.series
                    .first()
                    .sequence shouldBe "1"
                (viewModel.state.value.hasChanges) shouldBe true
            }
        }

        // ========== Genre Management Tests ==========

        test("selecting genre adds to book") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1")
                val genre = EditableGenre(id = "genre-1", name = "Fantasy", path = "/fiction/fantasy")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When
                viewModel.onEvent(BookEditUiEvent.GenreSelected(genre))

                // Then
                viewModel.state.value.genres.size shouldBe 1
                viewModel.state.value.genres
                    .first()
                    .name shouldBe "Fantasy"
                (viewModel.state.value.hasChanges) shouldBe true
            }
        }

        test("removing genre updates state") {
            runTest {
                // Given
                val fixture = createFixture()
                val genre = EditableGenre(id = "genre-1", name = "Fantasy", path = "/fiction/fantasy")
                val editData = createBookEditData(bookId = "book-1", genres = listOf(genre))
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()
                viewModel.state.value.genres.size shouldBe 1

                // When
                val editableGenre =
                    viewModel.state.value.genres
                        .first()
                viewModel.onEvent(BookEditUiEvent.RemoveGenre(editableGenre))

                // Then
                viewModel.state.value.genres.size shouldBe 0
                (viewModel.state.value.hasChanges) shouldBe true
            }
        }

        // ========== Tag Management Tests ==========

        test("selecting tag adds to book") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1")
                val tag = EditableTag(id = "tag-1", slug = "favorites")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When
                viewModel.onEvent(BookEditUiEvent.TagSelected(tag))

                // Then
                viewModel.state.value.tags.size shouldBe 1
                viewModel.state.value.tags
                    .first()
                    .displayName() shouldBe "Favorites"
                (viewModel.state.value.hasChanges) shouldBe true
            }
        }

        test("removing tag updates state") {
            runTest {
                // Given
                val fixture = createFixture()
                val tag = EditableTag(id = "tag-1", slug = "favorites")
                val editData = createBookEditData(bookId = "book-1", tags = listOf(tag))
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()
                viewModel.state.value.tags.size shouldBe 1

                // When
                val editableTag =
                    viewModel.state.value.tags
                        .first()
                viewModel.onEvent(BookEditUiEvent.RemoveTag(editableTag))

                // Then
                viewModel.state.value.tags.size shouldBe 0
                (viewModel.state.value.hasChanges) shouldBe true
            }
        }

        // ========== Mood Management Tests ==========

        test("selecting mood adds to book") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1")
                val mood = EditableMood(id = "mood-1", slug = "feel-good")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When
                viewModel.onEvent(BookEditUiEvent.MoodSelected(mood))

                // Then
                viewModel.state.value.moods.size shouldBe 1
                viewModel.state.value.moods
                    .first()
                    .displayName() shouldBe "Feel Good"
                (viewModel.state.value.hasChanges) shouldBe true
            }
        }

        test("removing mood updates state") {
            runTest {
                // Given
                val fixture = createFixture()
                val mood = EditableMood(id = "mood-1", slug = "feel-good")
                val editData = createBookEditData(bookId = "book-1", moods = listOf(mood))
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()
                viewModel.state.value.moods.size shouldBe 1

                // When
                val editableMood =
                    viewModel.state.value.moods
                        .first()
                viewModel.onEvent(BookEditUiEvent.RemoveMood(editableMood))

                // Then
                viewModel.state.value.moods.size shouldBe 0
                (viewModel.state.value.hasChanges) shouldBe true
            }
        }

        // ========== Save Tests ==========

        test("save with no changes navigates back without calling use case") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1", title = "Unchanged")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()
                (viewModel.state.value.hasChanges) shouldBe false

                // When / Then
                viewModel.navActions.test {
                    viewModel.onEvent(BookEditUiEvent.Save)
                    advanceUntilIdle()
                    awaitItem() shouldBe BookEditNavAction.NavigateBack
                }
            }
        }

        test("save with changes calls use case and navigates back") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1", title = "Original")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                everySuspend { fixture.updateBookUseCase(any(), any()) } returns AppResult.Success(Unit)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When / Then
                viewModel.navActions.test {
                    viewModel.onEvent(BookEditUiEvent.TitleChanged("Updated"))
                    viewModel.onEvent(BookEditUiEvent.Save)
                    advanceUntilIdle()
                    verifySuspend { fixture.updateBookUseCase(any(), any()) }
                    awaitItem() shouldBe BookEditNavAction.NavigateBack
                }
            }
        }

        test("save failure shows error") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1", title = "Original")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                everySuspend { fixture.updateBookUseCase(any(), any()) } returns failureOf("Save failed")
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When
                viewModel.onEvent(BookEditUiEvent.TitleChanged("Updated"))
                viewModel.onEvent(BookEditUiEvent.Save)
                advanceUntilIdle()

                // Then
                viewModel.state.value.error shouldBe "Save failed"
                // No nav action emitted on failure — Channel stays silent.
                viewModel.navActions.test {
                    expectNoEvents()
                }
            }
        }

        test("save failure emits typed AppError to ErrorBus for global snackbar") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1", title = "Original")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                everySuspend { fixture.updateBookUseCase(any(), any()) } returns failureOf("Save failed")
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When / Then
                fixture.errorBus.errors.test {
                    viewModel.onEvent(BookEditUiEvent.TitleChanged("Updated"))
                    viewModel.onEvent(BookEditUiEvent.Save)
                    advanceUntilIdle()
                    awaitItem().message shouldBe "Save failed"
                }
            }
        }

        test("cancel navigates back") {
            runTest {
                // Given
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When / Then
                viewModel.navActions.test {
                    viewModel.onEvent(BookEditUiEvent.Cancel)
                    awaitItem() shouldBe BookEditNavAction.NavigateBack
                }
            }
        }

        // ========== Admin Gate Tests ==========

        fun makeCollection(
            id: String,
            name: String,
        ): Collection =
            Collection(
                id = id,
                name = name,
                ownerId = "admin-1",
                isInbox = false,
                isSystem = false,
                bookCount = 0,
                callerPermission = SharePermission.Write,
                isOwner = true,
            )

        test("admin user reflects isAdmin true into state") {
            runTest {
                // Given
                val fixture = createFixture()
                every { fixture.userRepository.observeIsAdmin() } returns flowOf(true)
                val editData = createBookEditData(bookId = "book-1")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()

                // When
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // Then
                viewModel.state.value.isAdmin shouldBe true
            }
        }

        test("non-admin user reflects isAdmin false into state") {
            runTest {
                // Given — createFixture stubs observeIsAdmin() to flowOf(false) by default
                val fixture = createFixture()
                val editData = createBookEditData(bookId = "book-1")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                val viewModel = fixture.build()

                // When
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // Then
                viewModel.state.value.isAdmin shouldBe false
            }
        }

        // ========== Collection Save-Dispatch Tests ==========

        test("save with changed collection set dispatches setBookCollections") {
            runTest {
                // Given — one available collection, book starts with no memberships
                val fixture = createFixture()
                val collection = makeCollection(id = "coll-1", name = "Favorites")
                every { fixture.collectionRepository.observeCollections() } returns
                    flowOf(listOf(collection))
                every { fixture.collectionRepository.observeBookCollectionIds(any()) } returns
                    flowOf(emptyList())
                val editData = createBookEditData(bookId = "book-1", title = "Original")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                everySuspend { fixture.updateBookUseCase(any(), any()) } returns AppResult.Success(Unit)
                everySuspend { fixture.bookEditRepository.setBookCollections(any(), any()) } returns AppResult.Success(Unit)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When — add a collection so the set differs from the baseline, then save
                viewModel.onEvent(BookEditUiEvent.CollectionSelected(EditableCollection(id = "coll-1", name = "Favorites")))
                advanceUntilIdle()
                (viewModel.state.value.hasChanges) shouldBe true
                viewModel.onEvent(BookEditUiEvent.Save)
                advanceUntilIdle()

                // Then
                verifySuspend { fixture.bookEditRepository.setBookCollections(any(), any()) }
            }
        }

        test("save with unchanged collection set does not dispatch setBookCollections") {
            runTest {
                // Given — collection membership matches the baseline; only a metadata field changes
                val fixture = createFixture()
                val collection = makeCollection(id = "coll-1", name = "Favorites")
                every { fixture.collectionRepository.observeCollections() } returns
                    flowOf(listOf(collection))
                every { fixture.collectionRepository.observeBookCollectionIds(any()) } returns
                    flowOf(listOf("coll-1"))
                val editData = createBookEditData(bookId = "book-1", title = "Original")
                everySuspend { fixture.loadBookForEditUseCase("book-1") } returns AppResult.Success(editData)
                everySuspend { fixture.updateBookUseCase(any(), any()) } returns AppResult.Success(Unit)
                everySuspend { fixture.bookEditRepository.setBookCollections(any(), any()) } returns AppResult.Success(Unit)
                val viewModel = fixture.build()
                viewModel.loadBook("book-1")
                advanceUntilIdle()

                // When — change a metadata field (so Save proceeds) but leave collections untouched
                viewModel.onEvent(BookEditUiEvent.TitleChanged("Updated"))
                viewModel.onEvent(BookEditUiEvent.Save)
                advanceUntilIdle()

                // Then — the collection set never changed, so the access-aware RPC is skipped
                verifySuspend(VerifyMode.not) { fixture.bookEditRepository.setBookCollections(any(), any()) }
            }
        }

        test("dismiss error clears error state") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.loadBookForEditUseCase("nonexistent") } returns failureOf("Book not found")
                val viewModel = fixture.build()
                viewModel.loadBook("nonexistent")
                advanceUntilIdle()
                viewModel.state.value.error shouldBe "Book not found"

                // When
                viewModel.onEvent(BookEditUiEvent.DismissError)

                // Then
                viewModel.state.value.error shouldBe null
            }
        }
    })
