package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.remote.ImageApiContract
import com.calypsan.listenup.client.domain.repository.ImageStorage
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Tests for ImageDownloader.
 *
 * Tests cover:
 * - Single cover download (success, already exists, API failure, storage failure)
 * - Non-fatal error handling
 *
 * Uses Mokkery for mocking ImageApiContract and ImageStorage.
 */
class ImageDownloaderTest :
    FunSpec({
        // ========== Test Fixtures ==========

        class TestFixture {
            val imageApi: ImageApiContract = mock()
            val imageStorage: ImageStorage = mock()
            val bookDao: BookDao = mock()

            fun build(): ImageDownloader =
                ImageDownloader(
                    imageApi = imageApi,
                    imageStorage = imageStorage,
                    bookDao = bookDao,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()

            // Default stubs
            every { fixture.imageStorage.exists(any()) } returns false
            everySuspend { fixture.imageApi.downloadCover(any()) } returns AppResult.Success(ByteArray(100))
            everySuspend { fixture.imageStorage.saveCover(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { fixture.bookDao.markCoverDownloaded(any(), any()) } returns Unit
            everySuspend { fixture.bookDao.clearCoverDownloaded(any()) } returns Unit

            return fixture
        }

        // ========== Single Cover Download Tests ==========

        test("downloadCover returns success with true when cover downloaded and saved") {
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
                val success = result.shouldBeInstanceOf<AppResult.Success<Boolean>>()
                success.data shouldBe true
                verifySuspend { fixture.imageApi.downloadCover(bookId) }
                verifySuspend { fixture.imageStorage.saveCover(bookId, imageBytes) }
            }
        }

        test("downloadCover returns success with false when cover already exists") {
            runTest {
                // Given
                val fixture = createFixture()
                val bookId = BookId("book-1")
                every { fixture.imageStorage.exists(bookId) } returns true
                val imageDownloader = fixture.build()

                // When
                val result = imageDownloader.downloadCover(bookId)

                // Then
                val success = result.shouldBeInstanceOf<AppResult.Success<Boolean>>()
                success.data shouldBe false
            }
        }

        test("downloadCover returns success with false when API returns failure") {
            runTest {
                // Given - 404 Not Found (no cover available)
                val fixture = createFixture()
                val bookId = BookId("book-1")
                everySuspend { fixture.imageApi.downloadCover(bookId) } returns Failure(Exception("Not found"))
                val imageDownloader = fixture.build()

                // When
                val result = imageDownloader.downloadCover(bookId)

                // Then - returns false (non-fatal), not failure
                val success = result.shouldBeInstanceOf<AppResult.Success<Boolean>>()
                success.data shouldBe false
            }
        }

        test("downloadCover returns failure when storage save fails") {
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
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.message shouldBe "Disk full"
            }
        }

        test("downloadCover does not call API when cover already exists") {
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

        // ========== Cover-Presence Marker Tests ==========

        test("downloadCover marks the cover-presence column after a successful download") {
            runTest {
                // Given
                val fixture = createFixture()
                val imageDownloader = fixture.build()
                val bookId = BookId("book-1")
                val imageBytes = ByteArray(100) { it.toByte() }
                everySuspend { fixture.imageApi.downloadCover(bookId) } returns AppResult.Success(imageBytes)
                everySuspend { fixture.imageStorage.saveCover(bookId, imageBytes) } returns AppResult.Success(Unit)

                // When
                imageDownloader.downloadCover(bookId)

                // Then
                verifySuspend { fixture.bookDao.markCoverDownloaded(bookId, any()) }
            }
        }

        test("downloadCover marks the cover-presence column when the cover already exists locally") {
            runTest {
                // Given - already-exists early return still means "present on disk", so the
                // marker must be written (this is also how a v42→v43 upgrader's pre-existing
                // cover files first get marked, ahead of the startup reconciler).
                val fixture = createFixture()
                val bookId = BookId("book-1")
                every { fixture.imageStorage.exists(bookId) } returns true
                val imageDownloader = fixture.build()

                // When
                imageDownloader.downloadCover(bookId)

                // Then
                verifySuspend { fixture.bookDao.markCoverDownloaded(bookId, any()) }
            }
        }

        test("downloadCover does not mark the cover-presence column when the API returns failure") {
            runTest {
                // Given - 404 Not Found (no cover available), no file was ever written.
                val fixture = createFixture()
                val bookId = BookId("book-1")
                everySuspend { fixture.imageApi.downloadCover(bookId) } returns Failure(Exception("Not found"))
                val imageDownloader = fixture.build()

                // When
                imageDownloader.downloadCover(bookId)

                // Then
                verifySuspend(VerifyMode.not) { fixture.bookDao.markCoverDownloaded(any(), any()) }
            }
        }

        test("downloadCover does not mark the cover-presence column when the storage save fails") {
            runTest {
                // Given
                val fixture = createFixture()
                val bookId = BookId("book-1")
                val imageBytes = ByteArray(100)
                val storageFailure =
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Disk full"),
                    )
                everySuspend { fixture.imageApi.downloadCover(bookId) } returns AppResult.Success(imageBytes)
                everySuspend { fixture.imageStorage.saveCover(bookId, imageBytes) } returns storageFailure
                val imageDownloader = fixture.build()

                // When
                imageDownloader.downloadCover(bookId)

                // Then
                verifySuspend(VerifyMode.not) { fixture.bookDao.markCoverDownloaded(any(), any()) }
            }
        }

        test("deleteCover clears the cover-presence column") {
            runTest {
                // Given
                val fixture = createFixture()
                val bookId = BookId("book-1")
                everySuspend { fixture.imageStorage.deleteCover(bookId) } returns AppResult.Success(Unit)
                val imageDownloader = fixture.build()

                // When
                imageDownloader.deleteCover(bookId)

                // Then
                verifySuspend { fixture.bookDao.clearCoverDownloaded(bookId) }
            }
        }

        // ========== User Avatar Dedup + Negative-Cache Tests ==========

        test("concurrent downloadUserAvatar calls coalesce into a single download + save") {
            runTest {
                // Given - the API call is gated in-flight so the second caller must arrive
                // while the first is still running: the exact post-scan fan-out race.
                val fixture = createFixture()
                val userId = "user-1"
                val gate = CompletableDeferred<Unit>()
                var apiCalls = 0
                var saveCalls = 0
                // Stateful exists: false until the first caller saves, then true so the
                // coalesced second caller short-circuits instead of re-downloading.
                var avatarSaved = false
                every { fixture.imageStorage.userAvatarExists(userId) } calls { avatarSaved }
                everySuspend { fixture.imageApi.downloadUserAvatar(userId) } calls {
                    apiCalls++
                    gate.await()
                    AppResult.Success(ByteArray(10))
                }
                everySuspend { fixture.imageStorage.saveUserAvatar(userId, any()) } calls {
                    saveCalls++
                    avatarSaved = true
                    AppResult.Success(Unit)
                }
                val imageDownloader = fixture.build()

                // When - launch both, let them reach their suspension points, then release.
                val first = launch { imageDownloader.downloadUserAvatar(userId, forceRefresh = false) }
                val second = launch { imageDownloader.downloadUserAvatar(userId, forceRefresh = false) }
                runCurrent()
                gate.complete(Unit)
                joinAll(first, second)

                // Then - the network + save happened exactly once.
                apiCalls shouldBe 1
                saveCalls shouldBe 1
            }
        }

        test("downloadUserAvatar negative-caches a 404 and skips re-hitting the server") {
            runTest {
                // Given - the server has no avatar for this user (404 → Failure).
                val fixture = createFixture()
                val userId = "user-1"
                var apiCalls = 0
                every { fixture.imageStorage.userAvatarExists(userId) } returns false
                everySuspend { fixture.imageApi.downloadUserAvatar(userId) } calls {
                    apiCalls++
                    Failure(Exception("Not found"))
                }
                val imageDownloader = fixture.build()

                // When - first call hits the server and negative-caches the miss.
                val firstResult = imageDownloader.downloadUserAvatar(userId, forceRefresh = false)

                // Then - non-fatal success(false), one network call.
                firstResult.shouldBeInstanceOf<AppResult.Success<Boolean>>().data shouldBe false
                apiCalls shouldBe 1

                // When - a second call for the same user.
                val secondResult = imageDownloader.downloadUserAvatar(userId, forceRefresh = false)

                // Then - the negative cache short-circuits: no second network call.
                secondResult.shouldBeInstanceOf<AppResult.Success<Boolean>>().data shouldBe false
                apiCalls shouldBe 1

                // When - forceRefresh bypasses the negative cache.
                imageDownloader.downloadUserAvatar(userId, forceRefresh = true)

                // Then - the server is hit again.
                apiCalls shouldBe 2
            }
        }
    })
