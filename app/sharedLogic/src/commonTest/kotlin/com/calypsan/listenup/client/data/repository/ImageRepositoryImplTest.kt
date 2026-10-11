package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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

        // ---- ensureSeriesCoverCached: one fetch per series at a time ----

        class SeriesCoverRig(
            scope: CoroutineScope,
        ) {
            val started = mutableListOf<String>()
            val gates = mutableListOf<CompletableDeferred<Unit>>()
            val repo: ImageRepositoryImpl

            init {
                val imageDownloader: ImageDownloaderContract = mock()
                everySuspend { imageDownloader.downloadSeriesCover(any()) } calls { args ->
                    started += args.arg<String>(0)
                    val gate = CompletableDeferred<Unit>()
                    gates += gate
                    gate.await()
                    AppResult.Success(true)
                }
                repo =
                    ImageRepositoryImpl(
                        imageDownloader = imageDownloader,
                        imageStorage = mock(),
                        imageApi = mock(),
                        appScope = scope,
                    )
            }
        }

        test("ensureSeriesCoverCached launches exactly one series cover download") {
            runTest {
                val rig = SeriesCoverRig(this)

                rig.repo.ensureSeriesCoverCached("series-1")
                advanceUntilIdle()

                rig.started shouldBe listOf("series-1")
                rig.gates.single().complete(Unit)
                advanceUntilIdle()
            }
        }

        test("a second ensureSeriesCoverCached for the same series while one is in flight launches nothing") {
            runTest {
                val rig = SeriesCoverRig(this)

                rig.repo.ensureSeriesCoverCached("series-1")
                advanceUntilIdle()
                rig.repo.ensureSeriesCoverCached("series-1")
                advanceUntilIdle()

                rig.started shouldBe listOf("series-1")
                rig.gates.forEach { it.complete(Unit) }
                advanceUntilIdle()
            }
        }

        test("ensureSeriesCoverCached launches again once the first fetch has completed") {
            runTest {
                val rig = SeriesCoverRig(this)

                rig.repo.ensureSeriesCoverCached("series-1")
                advanceUntilIdle()
                rig.gates.single().complete(Unit)
                advanceUntilIdle()
                rig.repo.ensureSeriesCoverCached("series-1")
                advanceUntilIdle()

                rig.started shouldBe listOf("series-1", "series-1")
                rig.gates.last().complete(Unit)
                advanceUntilIdle()
            }
        }

        test("ensureSeriesCoverCached launches again after the first fetch failed") {
            runTest {
                val rig = SeriesCoverRig(this)

                rig.repo.ensureSeriesCoverCached("series-1")
                advanceUntilIdle()
                rig.gates.single().completeExceptionally(IllegalStateException("network down"))
                advanceUntilIdle()
                rig.repo.ensureSeriesCoverCached("series-1")
                advanceUntilIdle()

                rig.started shouldBe listOf("series-1", "series-1")
                rig.gates.last().complete(Unit)
                advanceUntilIdle()
            }
        }

        test("ensureSeriesCoverCached for two different series launches both") {
            runTest {
                val rig = SeriesCoverRig(this)

                rig.repo.ensureSeriesCoverCached("series-1")
                rig.repo.ensureSeriesCoverCached("series-2")
                advanceUntilIdle()

                rig.started.sorted() shouldBe listOf("series-1", "series-2")
                rig.gates.forEach { it.complete(Unit) }
                advanceUntilIdle()
            }
        }
    })
