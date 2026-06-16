package com.calypsan.listenup.client.domain.usecase.book

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.domain.model.BookEditData
import com.calypsan.listenup.client.domain.model.BookMetadata
import com.calypsan.listenup.client.domain.model.BookUpdateRequest
import com.calypsan.listenup.client.domain.model.PendingCover
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.presentation.bookedit.ContributorRole
import com.calypsan.listenup.client.presentation.bookedit.EditableContributor
import com.calypsan.listenup.client.presentation.bookedit.EditableGenre
import com.calypsan.listenup.client.presentation.bookedit.EditableMood
import com.calypsan.listenup.client.presentation.bookedit.EditableSeries
import com.calypsan.listenup.client.presentation.bookedit.EditableTag
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.api.result.failureOf
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.matcher.matches
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for UpdateBookUseCase.
 *
 * Tests cover:
 * - No changes detection (returns success without calling repos)
 * - Metadata change detection and update
 * - Contributor change detection and update
 * - Series change detection and update
 * - Genre change detection and update
 * - Tag change detection (add/remove)
 * - Cover upload handling
 * - Error propagation and fail-fast behavior
 * - Multiple changes in single call
 */
class UpdateBookUseCaseTest :
    FunSpec({

        // ========== Test Fixtures ==========

        class TestFixture {
            val bookEditRepository: BookEditRepository = mock()
            val tagRepository: TagRepository = mock()
            val moodRepository: MoodRepository = mock()
            val imageRepository: ImageRepository = mock()
            val imageStagingRepository: ImageStagingRepository = mock()

            fun build(): UpdateBookUseCase =
                UpdateBookUseCase(
                    bookEditRepository = bookEditRepository,
                    tagRepository = tagRepository,
                    moodRepository = moodRepository,
                    imageRepository = imageRepository,
                    imageStagingRepository = imageStagingRepository,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()

            // Default stubs for successful operations
            everySuspend { fixture.bookEditRepository.updateBook(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { fixture.bookEditRepository.setBookContributors(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { fixture.bookEditRepository.setBookSeries(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { fixture.bookEditRepository.deleteBookCover(any()) } returns AppResult.Success(Unit)
            everySuspend { fixture.bookEditRepository.setBookGenres(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { fixture.tagRepository.addTagToBook(any(), any()) } returns AppResult.Success(TestData.tag())
            everySuspend { fixture.tagRepository.removeTagFromBook(any(), any(), any()) } returns AppResult.Success(Unit)
            everySuspend { fixture.moodRepository.addMoodToBook(any(), any()) } returns AppResult.Success(TestData.mood())
            everySuspend { fixture.moodRepository.removeMoodFromBook(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { fixture.imageStagingRepository.commitBookCoverStaging(any()) } returns AppResult.Success(Unit)
            everySuspend { fixture.imageRepository.uploadBookCover(any(), any(), any()) } returns AppResult.Success("https://example.com/cover.jpg")

            return fixture
        }

        // ========== Test Data Factories ==========

        fun createMetadata(
            title: String = "Test Book",
            sortTitle: String = "",
            subtitle: String = "",
            description: String = "",
            publishYear: String = "",
            publisher: String = "",
            language: String? = null,
            isbn: String = "",
            asin: String = "",
            abridged: Boolean = false,
            addedAt: Long? = null,
        ): BookMetadata =
            BookMetadata(
                title = title,
                sortTitle = sortTitle,
                subtitle = subtitle,
                description = description,
                publishYear = publishYear,
                publisher = publisher,
                language = language,
                isbn = isbn,
                asin = asin,
                abridged = abridged,
                addedAt = addedAt,
            )

        fun createOriginalState(
            bookId: String = "book-1",
            metadata: BookMetadata = createMetadata(),
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
                metadata = metadata,
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

        fun createUpdateRequest(
            bookId: String = "book-1",
            metadata: BookMetadata = createMetadata(),
            contributors: List<EditableContributor> = emptyList(),
            series: List<EditableSeries> = emptyList(),
            genres: List<EditableGenre> = emptyList(),
            tags: List<EditableTag> = emptyList(),
            moods: List<EditableMood> = emptyList(),
            pendingCover: PendingCover? = null,
        ): BookUpdateRequest =
            BookUpdateRequest(
                bookId = bookId,
                metadata = metadata,
                contributors = contributors,
                series = series,
                genres = genres,
                tags = tags,
                moods = moods,
                pendingCover = pendingCover,
            )

        fun createEditableContributor(
            id: String = "c1",
            name: String = "Author Name",
            roles: Set<ContributorRole> = setOf(ContributorRole.AUTHOR),
        ): EditableContributor =
            EditableContributor(
                id = id,
                name = name,
                roles = roles,
            )

        fun createEditableSeries(
            id: String = "s1",
            name: String = "Series Name",
            sequence: String? = "1",
        ): EditableSeries =
            EditableSeries(
                id = id,
                name = name,
                sequence = sequence,
            )

        fun createEditableGenre(
            id: String = "g1",
            name: String = "Fiction",
            path: String = "/fiction",
        ): EditableGenre =
            EditableGenre(
                id = id,
                name = name,
                path = path,
            )

        fun createEditableTag(
            id: String = "t1",
            slug: String = "favorites",
        ): EditableTag =
            EditableTag(
                id = id,
                slug = slug,
            )

        fun createEditableMood(
            id: String = "m1",
            slug: String = "feel-good",
        ): EditableMood =
            EditableMood(
                id = id,
                slug = slug,
            )

        // ========== No Changes Tests ==========

        test("returns success without calling repos when no changes") {
            runTest {
                // Given - identical current and original state
                val metadata = createMetadata(title = "Same Title")
                val original = createOriginalState(metadata = metadata)
                val current = createUpdateRequest(metadata = metadata)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)

                // Verify no repository calls were made
                verifySuspend(VerifyMode.not) { fixture.bookEditRepository.updateBook(any(), any()) }
                verifySuspend(VerifyMode.not) { fixture.bookEditRepository.setBookContributors(any(), any()) }
                verifySuspend(VerifyMode.not) { fixture.bookEditRepository.setBookSeries(any(), any()) }
                verifySuspend(VerifyMode.not) { fixture.bookEditRepository.setBookGenres(any(), any()) }
            }
        }

        // ========== Metadata Change Tests ==========

        test("updates metadata when title changes") {
            runTest {
                // Given
                val originalMetadata = createMetadata(title = "Original Title")
                val currentMetadata = createMetadata(title = "New Title")
                val original = createOriginalState(metadata = originalMetadata)
                val current = createUpdateRequest(metadata = currentMetadata)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend {
                    fixture.bookEditRepository.updateBook(
                        BookId("book-1"),
                        matches { patch: BookUpdate -> patch.title == "New Title" },
                    )
                }
            }
        }

        test("updates metadata when any field changes") {
            runTest {
                // Given
                val originalMetadata = createMetadata()
                val currentMetadata =
                    createMetadata(
                        subtitle = "New Subtitle",
                        description = "New Description",
                        publishYear = "2024",
                        publisher = "New Publisher",
                        language = "en",
                        isbn = "1234567890",
                        asin = "ASIN123",
                        abridged = true,
                    )
                val original = createOriginalState(metadata = originalMetadata)
                val current = createUpdateRequest(metadata = currentMetadata)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend {
                    fixture.bookEditRepository.updateBook(
                        BookId("book-1"),
                        matches { patch: BookUpdate ->
                            patch.subtitle == "New Subtitle" &&
                                patch.description == "New Description" &&
                                patch.publishYear == 2024 &&
                                patch.publisher == "New Publisher" &&
                                patch.language == "en" &&
                                patch.isbn == "1234567890" &&
                                patch.asin == "ASIN123" &&
                                patch.abridged == true
                        },
                    )
                }
            }
        }

        test("includes edited addedAt in the metadata patch") {
            runTest {
                // Given — only the added date changes
                val originalMetadata = createMetadata(addedAt = 1_700_000_000_000L)
                val currentMetadata = createMetadata(addedAt = 1_500_000_000_000L)
                val original = createOriginalState(metadata = originalMetadata)
                val current = createUpdateRequest(metadata = currentMetadata)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend {
                    fixture.bookEditRepository.updateBook(
                        BookId("book-1"),
                        matches { patch: BookUpdate -> patch.addedAt == 1_500_000_000_000L },
                    )
                }
            }
        }

        // ========== Contributor Change Tests ==========

        test("updates contributors when list changes") {
            runTest {
                // Given
                val originalContributors =
                    listOf(
                        createEditableContributor(id = "c1", name = "Author 1"),
                    )
                val currentContributors =
                    listOf(
                        createEditableContributor(id = "c1", name = "Author 1"),
                        createEditableContributor(id = "c2", name = "Author 2"),
                    )
                val original = createOriginalState(contributors = originalContributors)
                val current = createUpdateRequest(contributors = currentContributors)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { fixture.bookEditRepository.setBookContributors(BookId("book-1"), any()) }
            }
        }

        test("does not update contributors when list unchanged") {
            runTest {
                // Given
                val contributors =
                    listOf(
                        createEditableContributor(id = "c1", name = "Author 1"),
                    )
                val original = createOriginalState(contributors = contributors)
                val current = createUpdateRequest(contributors = contributors)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                useCase(current, original)

                // Then
                verifySuspend(VerifyMode.not) { fixture.bookEditRepository.setBookContributors(any(), any()) }
            }
        }

        // ========== Series Change Tests ==========

        test("updates series when list changes") {
            runTest {
                // Given
                val originalSeries = emptyList<EditableSeries>()
                val currentSeries =
                    listOf(
                        createEditableSeries(id = "s1", name = "New Series", sequence = "1"),
                    )
                val original = createOriginalState(series = originalSeries)
                val current = createUpdateRequest(series = currentSeries)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { fixture.bookEditRepository.setBookSeries(BookId("book-1"), any()) }
            }
        }

        test("converts series sequence to wire position") {
            runTest {
                // Given
                val currentSeries =
                    listOf(
                        createEditableSeries(id = "s1", name = "Series", sequence = "2.5"),
                    )
                val original = createOriginalState()
                val current = createUpdateRequest(series = currentSeries)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                useCase(current, original)

                // Then
                verifySuspend {
                    fixture.bookEditRepository.setBookSeries(
                        BookId("book-1"),
                        listOf(BookSeriesInput(id = SeriesId("s1"), name = "Series", position = 2.5)),
                    )
                }
            }
        }

        // ========== Genre Change Tests ==========

        test("updates genres when list changes") {
            runTest {
                // Given
                val originalGenres = listOf(createEditableGenre(id = "g1", name = "Fiction"))
                val currentGenres =
                    listOf(
                        createEditableGenre(id = "g1", name = "Fiction"),
                        createEditableGenre(id = "g2", name = "Mystery"),
                    )
                val original = createOriginalState(genres = originalGenres)
                val current = createUpdateRequest(genres = currentGenres)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { fixture.bookEditRepository.setBookGenres(any(), any()) }
            }
        }

        test("does not update genres when list unchanged") {
            runTest {
                // Given
                val genres = listOf(createEditableGenre(id = "g1", name = "Fiction"))
                val original = createOriginalState(genres = genres)
                val current = createUpdateRequest(genres = genres)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                useCase(current, original)

                // Then
                verifySuspend(VerifyMode.not) { fixture.bookEditRepository.setBookGenres(any(), any()) }
            }
        }

        // ========== Tag Change Tests ==========

        test("adds new tags") {
            runTest {
                // Given
                val originalTags = listOf(createEditableTag(id = "t1", slug = "favorites"))
                val currentTags =
                    listOf(
                        createEditableTag(id = "t1", slug = "favorites"),
                        createEditableTag(id = "t2", slug = "to-read"),
                    )
                val original = createOriginalState(tags = originalTags)
                val current = createUpdateRequest(tags = currentTags)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { fixture.tagRepository.addTagToBook("book-1", "to-read") }
            }
        }

        test("removes deleted tags") {
            runTest {
                // Given
                val originalTags =
                    listOf(
                        createEditableTag(id = "t1", slug = "favorites"),
                        createEditableTag(id = "t2", slug = "to-read"),
                    )
                val currentTags = listOf(createEditableTag(id = "t1", slug = "favorites"))
                val original = createOriginalState(tags = originalTags)
                val current = createUpdateRequest(tags = currentTags)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { fixture.tagRepository.removeTagFromBook("book-1", "to-read", "t2") }
            }
        }

        test("adds and removes tags in single operation") {
            runTest {
                // Given
                val originalTags =
                    listOf(
                        createEditableTag(id = "t1", slug = "favorites"),
                        createEditableTag(id = "t2", slug = "to-read"),
                    )
                val currentTags =
                    listOf(
                        createEditableTag(id = "t1", slug = "favorites"),
                        createEditableTag(id = "t3", slug = "completed"),
                    )
                val original = createOriginalState(tags = originalTags)
                val current = createUpdateRequest(tags = currentTags)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { fixture.tagRepository.removeTagFromBook("book-1", "to-read", "t2") }
                verifySuspend { fixture.tagRepository.addTagToBook("book-1", "completed") }
            }
        }

        test("does not update tags when unchanged") {
            runTest {
                // Given
                val tags = listOf(createEditableTag(id = "t1", slug = "favorites"))
                val original = createOriginalState(tags = tags)
                val current = createUpdateRequest(tags = tags)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                useCase(current, original)

                // Then
                verifySuspend(VerifyMode.not) { fixture.tagRepository.addTagToBook(any(), any()) }
                verifySuspend(VerifyMode.not) { fixture.tagRepository.removeTagFromBook(any(), any(), any()) }
            }
        }

        // ========== Mood Change Tests ==========

        test("adds new moods") {
            runTest {
                // Given
                val originalMoods = listOf(createEditableMood(id = "m1", slug = "feel-good"))
                val currentMoods =
                    listOf(
                        createEditableMood(id = "m1", slug = "feel-good"),
                        createEditableMood(id = "m2", slug = "tense"),
                    )
                val original = createOriginalState(moods = originalMoods)
                val current = createUpdateRequest(moods = currentMoods)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { fixture.moodRepository.addMoodToBook("book-1", "tense") }
            }
        }

        test("removes deleted moods") {
            runTest {
                // Given
                val originalMoods =
                    listOf(
                        createEditableMood(id = "m1", slug = "feel-good"),
                        createEditableMood(id = "m2", slug = "tense"),
                    )
                val currentMoods = listOf(createEditableMood(id = "m1", slug = "feel-good"))
                val original = createOriginalState(moods = originalMoods)
                val current = createUpdateRequest(moods = currentMoods)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { fixture.moodRepository.removeMoodFromBook("book-1", "m2") }
            }
        }

        test("adds and removes moods in single operation") {
            runTest {
                // Given
                val originalMoods =
                    listOf(
                        createEditableMood(id = "m1", slug = "feel-good"),
                        createEditableMood(id = "m2", slug = "tense"),
                    )
                val currentMoods =
                    listOf(
                        createEditableMood(id = "m1", slug = "feel-good"),
                        createEditableMood(id = "m3", slug = "scary"),
                    )
                val original = createOriginalState(moods = originalMoods)
                val current = createUpdateRequest(moods = currentMoods)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { fixture.moodRepository.removeMoodFromBook("book-1", "m2") }
                verifySuspend { fixture.moodRepository.addMoodToBook("book-1", "scary") }
            }
        }

        test("does not update moods when unchanged") {
            runTest {
                // Given
                val moods = listOf(createEditableMood(id = "m1", slug = "feel-good"))
                val original = createOriginalState(moods = moods)
                val current = createUpdateRequest(moods = moods)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                useCase(current, original)

                // Then
                verifySuspend(VerifyMode.not) { fixture.moodRepository.addMoodToBook(any(), any()) }
                verifySuspend(VerifyMode.not) { fixture.moodRepository.removeMoodFromBook(any(), any()) }
            }
        }

        // ========== Cover Upload Tests ==========

        test("commits and uploads cover when pending") {
            runTest {
                // Given
                val pendingCover =
                    PendingCover(
                        data = byteArrayOf(1, 2, 3),
                        filename = "cover.jpg",
                    )
                val original = createOriginalState()
                val current = createUpdateRequest(pendingCover = pendingCover)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { fixture.imageStagingRepository.commitBookCoverStaging(BookId("book-1")) }
                verifySuspend { fixture.imageRepository.uploadBookCover("book-1", any(), "cover.jpg") }
            }
        }

        test("does not upload cover when no pending cover") {
            runTest {
                // Given
                val original = createOriginalState()
                val current = createUpdateRequest(pendingCover = null)

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                useCase(current, original)

                // Then
                verifySuspend(VerifyMode.not) { fixture.imageStagingRepository.commitBookCoverStaging(any()) }
                verifySuspend(VerifyMode.not) { fixture.imageRepository.uploadBookCover(any(), any(), any()) }
            }
        }

        test("continues save even if cover upload fails") {
            runTest {
                // Given - cover upload will fail
                val pendingCover =
                    PendingCover(
                        data = byteArrayOf(1, 2, 3),
                        filename = "cover.jpg",
                    )
                val original = createOriginalState()
                val current = createUpdateRequest(pendingCover = pendingCover)

                val fixture = createFixture()
                everySuspend { fixture.imageRepository.uploadBookCover(any(), any(), any()) } returns failureOf("Upload failed")
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then - should still succeed (cover upload is best-effort)
                checkIs<AppResult.Success<Unit>>(result)
            }
        }

        // ========== Error Handling Tests ==========

        test("returns failure when metadata update fails") {
            runTest {
                // Given
                val originalMetadata = createMetadata(title = "Original")
                val currentMetadata = createMetadata(title = "New")
                val original = createOriginalState(metadata = originalMetadata)
                val current = createUpdateRequest(metadata = currentMetadata)

                val fixture = createFixture()
                everySuspend { fixture.bookEditRepository.updateBook(any(), any()) } returns failureOf("Update failed")
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                (failure.message.contains("Update failed")) shouldBe true
            }
        }

        test("stops execution on first error") {
            runTest {
                // Given - metadata will fail, contributors also changed
                val originalMetadata = createMetadata(title = "Original")
                val currentMetadata = createMetadata(title = "New")
                val currentContributors = listOf(createEditableContributor())

                val original = createOriginalState(metadata = originalMetadata)
                val current =
                    createUpdateRequest(
                        metadata = currentMetadata,
                        contributors = currentContributors,
                    )

                val fixture = createFixture()
                everySuspend { fixture.bookEditRepository.updateBook(any(), any()) } returns failureOf("Metadata failed")
                val useCase = fixture.build()

                // When
                useCase(current, original)

                // Then - contributors should not be updated because metadata failed first
                verifySuspend(VerifyMode.not) { fixture.bookEditRepository.setBookContributors(any(), any()) }
            }
        }

        test("returns failure when contributor update fails") {
            runTest {
                // Given
                val currentContributors = listOf(createEditableContributor())
                val original = createOriginalState()
                val current = createUpdateRequest(contributors = currentContributors)

                val fixture = createFixture()
                everySuspend { fixture.bookEditRepository.setBookContributors(any(), any()) } returns failureOf("Contributor update failed")
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                (failure.message.contains("Contributor update failed")) shouldBe true
            }
        }

        test("returns failure when series update fails") {
            runTest {
                // Given
                val currentSeries = listOf(createEditableSeries())
                val original = createOriginalState()
                val current = createUpdateRequest(series = currentSeries)

                val fixture = createFixture()
                everySuspend { fixture.bookEditRepository.setBookSeries(any(), any()) } returns failureOf("Series update failed")
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                (failure.message.contains("Series update failed")) shouldBe true
            }
        }

        // ========== Multiple Changes Test ==========

        test("handles all changes in single call") {
            runTest {
                // Given - everything changed
                val originalMetadata = createMetadata(title = "Original Title")
                val currentMetadata = createMetadata(title = "New Title")

                val originalContributors = listOf(createEditableContributor(id = "c1", name = "Old Author"))
                val currentContributors = listOf(createEditableContributor(id = "c2", name = "New Author"))

                val originalSeries = emptyList<EditableSeries>()
                val currentSeries = listOf(createEditableSeries(id = "s1", name = "New Series"))

                val originalGenres = listOf(createEditableGenre(id = "g1", name = "Fiction"))
                val currentGenres = listOf(createEditableGenre(id = "g2", name = "Mystery"))

                val originalTags = listOf(createEditableTag(id = "t1", slug = "old-tag"))
                val currentTags = listOf(createEditableTag(id = "t2", slug = "new-tag"))

                val originalMoods = listOf(createEditableMood(id = "m1", slug = "old-mood"))
                val currentMoods = listOf(createEditableMood(id = "m2", slug = "new-mood"))

                val pendingCover = PendingCover(data = byteArrayOf(1), filename = "cover.jpg")

                val original =
                    createOriginalState(
                        metadata = originalMetadata,
                        contributors = originalContributors,
                        series = originalSeries,
                        genres = originalGenres,
                        tags = originalTags,
                        moods = originalMoods,
                    )
                val current =
                    createUpdateRequest(
                        metadata = currentMetadata,
                        contributors = currentContributors,
                        series = currentSeries,
                        genres = currentGenres,
                        tags = currentTags,
                        moods = currentMoods,
                        pendingCover = pendingCover,
                    )

                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(current, original)

                // Then - all operations should be called
                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { fixture.bookEditRepository.updateBook(any(), any()) }
                verifySuspend { fixture.bookEditRepository.setBookContributors(any(), any()) }
                verifySuspend { fixture.bookEditRepository.setBookSeries(any(), any()) }
                verifySuspend { fixture.bookEditRepository.setBookGenres(any(), any()) }
                verifySuspend { fixture.tagRepository.removeTagFromBook(any(), any(), any()) }
                verifySuspend { fixture.tagRepository.addTagToBook(any(), any()) }
                verifySuspend { fixture.moodRepository.removeMoodFromBook(any(), any()) }
                verifySuspend { fixture.moodRepository.addMoodToBook(any(), any()) }
                verifySuspend { fixture.imageStagingRepository.commitBookCoverStaging(any()) }
                verifySuspend { fixture.imageRepository.uploadBookCover(any(), any(), any()) }
            }
        }
    })
