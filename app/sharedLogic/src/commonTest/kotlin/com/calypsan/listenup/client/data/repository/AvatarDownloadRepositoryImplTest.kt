package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
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
 * Tests for AvatarDownloadRepositoryImpl.
 *
 * Verifies that each repository method delegates to [ImageDownloaderContract] with the
 * correct arguments and that async work fires on the provided scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarDownloadRepositoryImplTest :
    FunSpec({
        test("queueAvatarDownload triggers download with forceRefresh=false") {
            runTest {
                val imageDownloader: ImageDownloaderContract = mock()
                everySuspend { imageDownloader.downloadUserAvatar(any(), any()) } returns AppResult.Success(false)
                val repo = AvatarDownloadRepositoryImpl(imageDownloader, this)

                repo.queueAvatarDownload("user-1")
                advanceUntilIdle()

                verifySuspend { imageDownloader.downloadUserAvatar("user-1", forceRefresh = false) }
            }
        }

        test("queueAvatarForceRefresh triggers download with forceRefresh=true") {
            runTest {
                val imageDownloader: ImageDownloaderContract = mock()
                everySuspend { imageDownloader.downloadUserAvatar(any(), any()) } returns AppResult.Success(true)
                val repo = AvatarDownloadRepositoryImpl(imageDownloader, this)

                repo.queueAvatarForceRefresh("user-1")
                advanceUntilIdle()

                verifySuspend { imageDownloader.downloadUserAvatar("user-1", forceRefresh = true) }
            }
        }

        test("deleteAvatar delegates to imageDownloader deleteUserAvatar") {
            runTest {
                val imageDownloader: ImageDownloaderContract = mock()
                everySuspend { imageDownloader.deleteUserAvatar(any()) } returns AppResult.Success(Unit)
                val repo = AvatarDownloadRepositoryImpl(imageDownloader, this)

                repo.deleteAvatar("user-1")

                verifySuspend { imageDownloader.deleteUserAvatar("user-1") }
            }
        }
    })
