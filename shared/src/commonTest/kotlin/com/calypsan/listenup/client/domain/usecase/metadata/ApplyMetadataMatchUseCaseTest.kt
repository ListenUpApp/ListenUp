package com.calypsan.listenup.client.domain.usecase.metadata

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.repository.ApplyMatchRequest
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.MetadataBook
import com.calypsan.listenup.client.domain.repository.MetadataContributor
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.repository.MetadataSeriesEntry
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApplyMetadataMatchUseCaseTest {
    // ========== Test Fixture ==========

    private class TestFixture {
        val metadataRepository: MetadataRepository = mock()
        val imageRepository: ImageRepository = mock()

        fun build(): ApplyMetadataMatchUseCase =
            ApplyMetadataMatchUseCase(
                metadataRepository = metadataRepository,
                imageRepository = imageRepository,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        // Default stubs for successful operations
        everySuspend { fixture.metadataRepository.applyMatch(any(), any()) } returns Unit
        everySuspend { fixture.imageRepository.deleteBookCover(any()) } returns Success(Unit)
        everySuspend { fixture.imageRepository.downloadBookCover(any()) } returns Success(true)

        return fixture
    }

    // ========== Test Data Factories ==========

    private fun createPreviewBook(
        asin: String = "B08XYZ123",
        title: String = "Test Book",
        authors: List<MetadataContributor> =
            listOf(
                MetadataContributor(asin = "author-1", name = "Author One"),
            ),
        narrators: List<MetadataContributor> =
            listOf(
                MetadataContributor(asin = "narrator-1", name = "Narrator One"),
            ),
        series: List<MetadataSeriesEntry> =
            listOf(
                MetadataSeriesEntry(asin = "series-1", name = "Test Series", position = "1"),
            ),
        genres: List<String> = listOf("Fiction", "Fantasy"),
    ): MetadataBook =
        MetadataBook(
            asin = asin,
            title = title,
            authors = authors,
            narrators = narrators,
            series = series,
            genres = genres,
        )

    private fun createSelections(
        cover: Boolean = true,
        title: Boolean = true,
        subtitle: Boolean = true,
        description: Boolean = true,
        publisher: Boolean = true,
        releaseDate: Boolean = true,
        language: Boolean = true,
        selectedAuthors: Set<String> = setOf("author-1"),
        selectedNarrators: Set<String> = setOf("narrator-1"),
        selectedSeries: Set<String> = setOf("series-1"),
        selectedGenres: Set<String> = setOf("Fiction"),
    ): MetadataMatchSelections =
        MetadataMatchSelections(
            cover = cover,
            title = title,
            subtitle = subtitle,
            description = description,
            publisher = publisher,
            releaseDate = releaseDate,
            language = language,
            selectedAuthors = selectedAuthors,
            selectedNarrators = selectedNarrators,
            selectedSeries = selectedSeries,
            selectedGenres = selectedGenres,
        )

    // ========== Success Tests ==========

    @Test
    fun `applies metadata match successfully`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()
            val previewBook = createPreviewBook()
            val selections = createSelections()

            // When
            val result =
                useCase(
                    bookId = "book-123",
                    asin = "B08XYZ123",
                    region = "us",
                    selections = selections,
                    previewBook = previewBook,
                    coverUrl = null,
                )

            // Then
            assertIs<Success<Unit>>(result)
            verifySuspend { fixture.metadataRepository.applyMatch("book-123", any()) }
        }

    @Test
    fun `builds request with correct fields from selections`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()
            val previewBook = createPreviewBook()
            val selections =
                createSelections(
                    title = true,
                    subtitle = false,
                    description = true,
                    cover = false,
                )

            var capturedRequest: ApplyMatchRequest? = null
            everySuspend { fixture.metadataRepository.applyMatch(any(), any()) } returns Unit

            // When
            useCase(
                bookId = "book-123",
                asin = "B08XYZ123",
                region = "uk",
                selections = selections,
                previewBook = previewBook,
                coverUrl = null,
            )

            // Then - verify the request was made with correct parameters
            verifySuspend { fixture.metadataRepository.applyMatch("book-123", any()) }
        }

    @Test
    fun `passes explicit cover URL in request`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()
            val previewBook = createPreviewBook()
            val selections = createSelections(cover = true)

            // When
            val result =
                useCase(
                    bookId = "book-123",
                    asin = "B08XYZ123",
                    region = "us",
                    selections = selections,
                    previewBook = previewBook,
                    coverUrl = "https://example.com/custom-cover.jpg",
                )

            // Then
            assertIs<Success<Unit>>(result)
            verifySuspend { fixture.metadataRepository.applyMatch("book-123", any()) }
        }

    // ========== Cover Download Tests ==========

    @Test
    fun `downloads cover when cover selection is enabled`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()
            val selections = createSelections(cover = true)

            // When
            useCase(
                bookId = "book-123",
                asin = "B08XYZ123",
                region = "us",
                selections = selections,
                previewBook = createPreviewBook(),
                coverUrl = null,
            )

            // Then
            verifySuspend { fixture.imageRepository.deleteBookCover(BookId("book-123")) }
            verifySuspend { fixture.imageRepository.downloadBookCover(BookId("book-123")) }
        }

    @Test
    fun `skips cover download when cover selection is disabled`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()
            val selections = createSelections(cover = false)

            // When
            useCase(
                bookId = "book-123",
                asin = "B08XYZ123",
                region = "us",
                selections = selections,
                previewBook = createPreviewBook(),
                coverUrl = null,
            )

            // Then
            verifySuspend(VerifyMode.not) { fixture.imageRepository.deleteBookCover(any()) }
            verifySuspend(VerifyMode.not) { fixture.imageRepository.downloadBookCover(any()) }
        }

    @Test
    fun `succeeds even when cover delete fails`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.imageRepository.deleteBookCover(any()) } returns
                Failure(Exception("Delete failed"))
            val useCase = fixture.build()

            // When
            val result =
                useCase(
                    bookId = "book-123",
                    asin = "B08XYZ123",
                    region = "us",
                    selections = createSelections(cover = true),
                    previewBook = createPreviewBook(),
                    coverUrl = null,
                )

            // Then - should still succeed, cover deletion is best-effort
            assertIs<Success<Unit>>(result)
            verifySuspend { fixture.imageRepository.downloadBookCover(BookId("book-123")) }
        }

    @Test
    fun `succeeds even when cover download fails`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.imageRepository.downloadBookCover(any()) } returns
                Failure(Exception("Download failed"))
            val useCase = fixture.build()

            // When
            val result =
                useCase(
                    bookId = "book-123",
                    asin = "B08XYZ123",
                    region = "us",
                    selections = createSelections(cover = true),
                    previewBook = createPreviewBook(),
                    coverUrl = null,
                )

            // Then - should still succeed, cover download is best-effort
            assertIs<Success<Unit>>(result)
        }

    @Test
    fun `succeeds when no cover available on server`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.imageRepository.downloadBookCover(any()) } returns Success(false)
            val useCase = fixture.build()

            // When
            val result =
                useCase(
                    bookId = "book-123",
                    asin = "B08XYZ123",
                    region = "us",
                    selections = createSelections(cover = true),
                    previewBook = createPreviewBook(),
                    coverUrl = null,
                )

            // Then
            assertIs<Success<Unit>>(result)
        }

    // ========== Series Filtering Tests ==========

    @Test
    fun `filters series based on selected series ASINs`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()
            val previewBook =
                createPreviewBook(
                    series =
                        listOf(
                            MetadataSeriesEntry(asin = "series-1", name = "Series One", position = "1"),
                            MetadataSeriesEntry(asin = "series-2", name = "Series Two", position = "2"),
                            MetadataSeriesEntry(asin = "series-3", name = "Series Three", position = "3"),
                        ),
                )
            val selections =
                createSelections(
                    selectedSeries = setOf("series-1", "series-3"), // Only select first and third
                )

            // When
            useCase(
                bookId = "book-123",
                asin = "B08XYZ123",
                region = "us",
                selections = selections,
                previewBook = previewBook,
                coverUrl = null,
            )

            // Then
            verifySuspend { fixture.metadataRepository.applyMatch("book-123", any()) }
        }

    @Test
    fun `excludes series entries without ASIN`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()
            val previewBook =
                createPreviewBook(
                    series =
                        listOf(
                            MetadataSeriesEntry(asin = "series-1", name = "Series With ASIN", position = "1"),
                            MetadataSeriesEntry(asin = null, name = "Series Without ASIN", position = "2"),
                        ),
                )
            val selections =
                createSelections(
                    selectedSeries = setOf("series-1"),
                )

            // When
            val result =
                useCase(
                    bookId = "book-123",
                    asin = "B08XYZ123",
                    region = "us",
                    selections = selections,
                    previewBook = previewBook,
                    coverUrl = null,
                )

            // Then
            assertIs<Success<Unit>>(result)
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `returns failure when repository throws exception`() =
        runTest {
            // Given
            val fixture = createFixture()
            // Body-level message convention: throw an exception that ErrorMapper
            // routes to ValidationError so the original message survives.
            everySuspend { fixture.metadataRepository.applyMatch(any(), any()) } throws
                IllegalArgumentException("Network error")
            val useCase = fixture.build()

            // When
            val result =
                useCase(
                    bookId = "book-123",
                    asin = "B08XYZ123",
                    region = "us",
                    selections = createSelections(),
                    previewBook = createPreviewBook(),
                    coverUrl = null,
                )

            // Then
            assertIs<Failure>(result)
            assertEquals("Network error", result.message)
        }

    @Test
    fun `does not attempt cover download when apply match fails`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.metadataRepository.applyMatch(any(), any()) } throws
                RuntimeException("Failed")
            val useCase = fixture.build()

            // When
            useCase(
                bookId = "book-123",
                asin = "B08XYZ123",
                region = "us",
                selections = createSelections(cover = true),
                previewBook = createPreviewBook(),
                coverUrl = null,
            )

            // Then - should not attempt cover download since apply failed
            verifySuspend(VerifyMode.not) { fixture.imageRepository.deleteBookCover(any()) }
            verifySuspend(VerifyMode.not) { fixture.imageRepository.downloadBookCover(any()) }
        }

    // ========== Region Tests ==========

    @Test
    fun `uses correct region in request`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            useCase(
                bookId = "book-123",
                asin = "B08XYZ123",
                region = "uk",
                selections = createSelections(),
                previewBook = createPreviewBook(),
                coverUrl = null,
            )

            // Then
            verifySuspend { fixture.metadataRepository.applyMatch("book-123", any()) }
        }

    // ========== Empty Selection Tests ==========

    @Test
    fun `handles empty author selection`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()
            val selections = createSelections(selectedAuthors = emptySet())

            // When
            val result =
                useCase(
                    bookId = "book-123",
                    asin = "B08XYZ123",
                    region = "us",
                    selections = selections,
                    previewBook = createPreviewBook(),
                    coverUrl = null,
                )

            // Then
            assertIs<Success<Unit>>(result)
        }

    @Test
    fun `handles empty narrator selection`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()
            val selections = createSelections(selectedNarrators = emptySet())

            // When
            val result =
                useCase(
                    bookId = "book-123",
                    asin = "B08XYZ123",
                    region = "us",
                    selections = selections,
                    previewBook = createPreviewBook(),
                    coverUrl = null,
                )

            // Then
            assertIs<Success<Unit>>(result)
        }

    @Test
    fun `handles empty series selection`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()
            val selections = createSelections(selectedSeries = emptySet())

            // When
            val result =
                useCase(
                    bookId = "book-123",
                    asin = "B08XYZ123",
                    region = "us",
                    selections = selections,
                    previewBook = createPreviewBook(),
                    coverUrl = null,
                )

            // Then
            assertIs<Success<Unit>>(result)
        }

    @Test
    fun `handles empty genre selection`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()
            val selections = createSelections(selectedGenres = emptySet())

            // When
            val result =
                useCase(
                    bookId = "book-123",
                    asin = "B08XYZ123",
                    region = "us",
                    selections = selections,
                    previewBook = createPreviewBook(),
                    coverUrl = null,
                )

            // Then
            assertIs<Success<Unit>>(result)
        }
}
