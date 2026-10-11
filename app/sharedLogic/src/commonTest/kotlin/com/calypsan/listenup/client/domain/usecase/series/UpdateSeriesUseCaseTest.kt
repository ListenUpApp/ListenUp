package com.calypsan.listenup.client.domain.usecase.series

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.failureOf
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.PendingImageKind
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import com.calypsan.listenup.client.domain.usecase.SaveOutcome
import com.calypsan.listenup.client.test.fake.FakePendingImageUploads
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for [UpdateSeriesUseCase]: a save whose cover upload failed still saves the series and
 * keeps the cover on the device, and says so instead of reporting a plain success.
 */
class UpdateSeriesUseCaseTest :
    FunSpec({

        class TestFixture {
            val seriesEditRepository: SeriesEditRepository = mock()
            val imageRepository: ImageRepository = mock()
            val imageStagingRepository: ImageStagingRepository = mock()
            val pendingImageUploads = FakePendingImageUploads()

            fun build() = UpdateSeriesUseCase(seriesEditRepository, imageRepository, imageStagingRepository, pendingImageUploads)
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()
            everySuspend { fixture.seriesEditRepository.updateSeries(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { fixture.imageStagingRepository.commitSeriesCoverStaging(any()) } returns AppResult.Success(Unit)
            everySuspend {
                fixture.imageRepository.uploadSeriesCover(any(), any(), any())
            } returns AppResult.Success("https://example.com/series.jpg")
            return fixture
        }

        fun requestWithCover() =
            SeriesUpdateRequest(
                seriesId = "series-1",
                name = "New Name",
                metadataChanged = true,
                nameChanged = true,
                pendingCoverData = byteArrayOf(1, 2, 3),
                pendingCoverFilename = "cover.jpg",
            )

        test("a failed cover upload still saves the metadata and commits the cover locally") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.imageRepository.uploadSeriesCover(any(), any(), any()) } returns failureOf("Upload failed")

                val result = fixture.build()(requestWithCover())

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                verifySuspend { fixture.seriesEditRepository.updateSeries(any(), any()) }
                verifySuspend { fixture.imageStagingRepository.commitSeriesCoverStaging("series-1") }
            }
        }

        test("a failed cover upload is reported as saved-but-not-uploaded") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.imageRepository.uploadSeriesCover(any(), any(), any()) } returns failureOf("Upload failed")

                val result = fixture.build()(requestWithCover())

                result
                    .shouldBeInstanceOf<AppResult.Success<*>>()
                    .data
                    .shouldBeInstanceOf<SaveOutcome.SavedImageNotUploaded>()
            }
        }

        test("a successful cover upload is reported as plainly saved") {
            runTest {
                val fixture = createFixture()

                val result = fixture.build()(requestWithCover())

                result.shouldBeInstanceOf<AppResult.Success<*>>().data shouldBe SaveOutcome.Saved
            }
        }

        test("a cover upload that failed for a transient reason is queued to retry") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.imageRepository.uploadSeriesCover(any(), any(), any()) } returns
                    AppResult.Failure(TransportError.Timeout())

                val result = fixture.build()(requestWithCover())

                fixture.pendingImageUploads.pending shouldBe setOf(PendingImageKind.SeriesCover to "series-1")
                result
                    .shouldBeInstanceOf<AppResult.Success<*>>()
                    .data
                    .shouldBeInstanceOf<SaveOutcome.SavedImageNotUploaded>()
                    .willRetry shouldBe true
            }
        }

        test("a cover upload the server refused is not queued") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.imageRepository.uploadSeriesCover(any(), any(), any()) } returns
                    AppResult.Failure(AuthError.PermissionDenied())

                fixture.build()(requestWithCover())

                fixture.pendingImageUploads.pending shouldBe emptySet()
            }
        }

        test("a cover upload that goes through clears an older queued upload for the same series") {
            runTest {
                val fixture = createFixture()
                fixture.pendingImageUploads.record(PendingImageKind.SeriesCover, "series-1")

                fixture.build()(requestWithCover())

                fixture.pendingImageUploads.pending shouldBe emptySet()
            }
        }

        test("a metadata failure fails the whole save") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.seriesEditRepository.updateSeries(any(), any()) } returns failureOf("Nope")

                fixture.build()(requestWithCover()).shouldBeInstanceOf<AppResult.Failure>()
            }
        }
    })
