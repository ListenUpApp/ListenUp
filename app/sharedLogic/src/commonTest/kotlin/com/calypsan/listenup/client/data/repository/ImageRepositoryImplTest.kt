package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Tests for [ImageRepositoryImpl].
 *
 * Covers the fire-and-forget [com.calypsan.listenup.client.domain.repository.ImageRepository.ensureBookCoverCached]
 * trigger that backs lazy offline cover persistence: it must launch a durable cover download on the
 * app scope so a streamed cover survives offline (independent of Coil/Nuke's evictable caches).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImageRepositoryImplTest :
    FunSpec({
        test("ensureBookCoverCached launches a durable cover download on the app scope") {
            runTest {
                val imageDownloader: ImageDownloaderContract = mock()
                everySuspend { imageDownloader.downloadCover(any()) } returns AppResult.Success(true)
                val repo =
                    ImageRepositoryImpl(
                        imageDownloader = imageDownloader,
                        imageStorage = mock(),
                        imageApi = mock(),
                        appScope = this,
                    )

                repo.ensureBookCoverCached(BookId("book-1"))
                advanceUntilIdle()

                verifySuspend { imageDownloader.downloadCover(BookId("book-1")) }
            }
        }

        test("ensureContributorImageCached launches a durable contributor-image download on the app scope") {
            runTest {
                val imageDownloader: ImageDownloaderContract = mock()
                everySuspend { imageDownloader.downloadContributorImage(any()) } returns AppResult.Success(true)
                val repo =
                    ImageRepositoryImpl(
                        imageDownloader = imageDownloader,
                        imageStorage = mock(),
                        imageApi = mock(),
                        appScope = this,
                    )

                repo.ensureContributorImageCached("contrib-1")
                advanceUntilIdle()

                verifySuspend { imageDownloader.downloadContributorImage("contrib-1") }
            }
        }

        test("ensureUserAvatarCached launches a durable avatar download on the app scope") {
            runTest {
                val imageDownloader: ImageDownloaderContract = mock()
                everySuspend { imageDownloader.downloadUserAvatar(any(), any()) } returns AppResult.Success(true)
                val repo =
                    ImageRepositoryImpl(
                        imageDownloader = imageDownloader,
                        imageStorage = mock(),
                        imageApi = mock(),
                        appScope = this,
                    )

                repo.ensureUserAvatarCached("user-1")
                advanceUntilIdle()

                verifySuspend { imageDownloader.downloadUserAvatar("user-1", forceRefresh = false) }
            }
        }
    })
