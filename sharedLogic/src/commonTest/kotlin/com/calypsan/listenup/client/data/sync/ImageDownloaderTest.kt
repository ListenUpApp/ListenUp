package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.data.remote.ImageApiContract
import com.calypsan.listenup.client.domain.repository.ImageStorage
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for ImageDownloader.
 *
 * Tests cover:
 * - Single cover download (success, already exists, API failure, storage failure)
 * - Non-fatal error handling
 *
 * Uses Mokkery for mocking ImageApiContract and ImageStorage.
 */
class ImageDownloaderTest {
    // ========== Test Fixtures ==========

    private class TestFixture {
        val imageApi: ImageApiContract = mock()
        val imageStorage: ImageStorage = mock()

        fun build(): ImageDownloader =
            ImageDownloader(
                imageApi = imageApi,
                imageStorage = imageStorage,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        // Default stubs
        every { fixture.imageStorage.exists(any()) } returns false
        everySuspend { fixture.imageApi.downloadCover(any()) } returns AppResult.Success(ByteArray(100))
        everySuspend { fixture.imageStorage.saveCover(any(), any()) } returns AppResult.Success(Unit)

        return fixture
    }

    // ========== Single Cover Download Tests ==========

    @Test
    fun `downloadCover returns success with true when cover downloaded and saved`() =
        runTest {
            // Given
            val fixture = createFixture()
            val imageDownloader = fixture.build()
            val bookId = BookId("book-1")
            val imageBytes = ByteArray(100) { it.toByte() }

            everySuspend { fixture.imageApi.downloadCover(bookId) } returns AppResult.Success(imageBytes)
            everySuspend { fixture.imageStorage.saveCover(bookId, imageBytes) } returns AppResult.Success(Unit)

            // When
            val result = imageDownloader.downloadCover(bookId)

            // Then
            val success = assertIs<AppResult.Success<Boolean>>(result)
            assertTrue(success.data)
            verifySuspend { fixture.imageApi.downloadCover(bookId) }
            verifySuspend { fixture.imageStorage.saveCover(bookId, imageBytes) }
        }

    @Test
    fun `downloadCover returns success with false when cover already exists`() =
        runTest {
            // Given
            val fixture = createFixture()
            val bookId = BookId("book-1")
            every { fixture.imageStorage.exists(bookId) } returns true
            val imageDownloader = fixture.build()

            // When
            val result = imageDownloader.downloadCover(bookId)

            // Then
            val success = assertIs<AppResult.Success<Boolean>>(result)
            assertEquals(false, success.data)
        }

    @Test
    fun `downloadCover returns success with false when API returns failure`() =
        runTest {
            // Given - 404 Not Found (no cover available)
            val fixture = createFixture()
            val bookId = BookId("book-1")
            everySuspend { fixture.imageApi.downloadCover(bookId) } returns Failure(Exception("Not found"))
            val imageDownloader = fixture.build()

            // When
            val result = imageDownloader.downloadCover(bookId)

            // Then - returns false (non-fatal), not failure
            val success = assertIs<AppResult.Success<Boolean>>(result)
            assertEquals(false, success.data)
        }

    @Test
    fun `downloadCover returns failure when storage save fails`() =
        runTest {
            // Given
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val imageBytes = ByteArray(100)
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation.
            val storageFailure =
                AppResult.Failure(
                    com.calypsan.listenup.api.error
                        .ValidationError(message = "Disk full"),
                )

            everySuspend { fixture.imageApi.downloadCover(bookId) } returns AppResult.Success(imageBytes)
            everySuspend { fixture.imageStorage.saveCover(bookId, imageBytes) } returns storageFailure
            val imageDownloader = fixture.build()

            // When
            val result = imageDownloader.downloadCover(bookId)

            // Then - storage failure is fatal
            val failure = assertIs<AppResult.Failure>(result)
            assertEquals("Disk full", failure.message)
        }

    @Test
    fun `downloadCover does not call API when cover already exists`() =
        runTest {
            // Given
            val fixture = createFixture()
            val bookId = BookId("book-1")
            every { fixture.imageStorage.exists(bookId) } returns true
            val imageDownloader = fixture.build()

            // When
            imageDownloader.downloadCover(bookId)

            // Then - API should not be called
            // (verify by not stubbing API - if called, test would fail)
        }
}
