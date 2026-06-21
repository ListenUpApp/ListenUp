package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.result.failureOf
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.model.AudioFileResponse
import com.calypsan.listenup.client.data.remote.model.BookResponse
import com.calypsan.listenup.client.data.repository.BookIngestPort
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest

/**
 * Proves [PlaybackPreparer.fetchBookFromServer] delegates the write to
 * [BookIngestPort.upsertWithAudioFiles], which owns the atomicity guarantee.
 *
 * The actual rollback behaviour is exercised in [BookRepositoryImplTest].
 * This test confirms the delegation wiring so the two tests together give
 * full coverage of the data path.
 *
 * Landed as part of W4 Item B (direct DAO writes); updated in W7 Phase B
 * Task 3 to reflect the route-through-repo refactor (drift #9).
 */
class PlaybackManagerFallbackFetchAtomicityTest :
    FunSpec({
        // Minimal-valid [BookResponse] factory. Only `id` and `audioFiles` matter for
        // this test — everything else is defaulted to empty/null/zero so the test is
        // insulated from future BookResponse field additions as long as they carry
        // their own defaults.
        //
        // Mirrors the shape used by BookPullerTest.createBookResponse and
        // BookPullerAtomicityTest's inline construction.
        fun bookResponseWithAudioFiles(
            id: String,
            audioFiles: List<AudioFileResponse>,
        ): BookResponse =
            BookResponse(
                id = id,
                title = "Rollback Test",
                subtitle = null,
                coverImage = null,
                totalDuration = 3_600_000L,
                description = null,
                genres = null,
                publishYear = null,
                seriesInfo = emptyList(),
                chapters = emptyList(),
                audioFiles = audioFiles,
                contributors = emptyList(),
                createdAt = "2024-01-01T00:00:00Z",
                updatedAt = "2024-01-01T00:00:00Z",
            )

        test("fetchBookFromServer delegates write to bookRepository upsertWithAudioFiles") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val syncApi: SyncApiContract = mock()
                    val bookIngestPort: BookIngestPort = mock()

                    everySuspend { bookIngestPort.upsertWithAudioFiles(any(), any()) } returns AppResult.Success(Unit)

                    everySuspend { syncApi.getBook(any()) } returns
                        AppResult.Success(
                            bookResponseWithAudioFiles(
                                id = "book-rollback",
                                audioFiles =
                                    listOf(
                                        AudioFileResponse(
                                            id = "af-1",
                                            filename = "chapter01.m4b",
                                            format = "m4b",
                                            codec = "aac",
                                            duration = 1_800_000L,
                                            size = 45_000_000L,
                                        ),
                                    ),
                            ),
                        )

                    // ProgressTracker is a final class — use the shared helper from PlaybackManagerTestSupport.
                    val preparer =
                        PlaybackPreparer(
                            serverConfig = mock(),
                            playbackPreferences = mock(),
                            bookDao = db.bookDao(),
                            audioFileDao = db.audioFileDao(),
                            chapterDao = db.chapterDao(),
                            imageStorage = mock(),
                            progressTracker = buildProgressTracker(),
                            tokenProvider = mock(),
                            deviceContext = DeviceContext(type = DeviceType.Phone),
                            downloadService = mock(),
                            playbackRpcFactory = testPlaybackRpcFactory("af-1"),
                            syncApi = syncApi,
                            scope = CoroutineScope(Job()),
                            bookIngestPort = bookIngestPort,
                        )

                    val result = preparer.fetchBookFromServer(BookId("book-rollback"))

                    withClue("fetchBookFromServer should return true on success") { result shouldBe true }
                    verifySuspend(VerifyMode.exactly(1)) {
                        bookIngestPort.upsertWithAudioFiles(any<BookEntity>(), any<List<AudioFileEntity>>())
                    }
                }
            } finally {
                db.close()
            }
        }

        test("fetchBookFromServer returns false when upsertWithAudioFiles returns Failure") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val syncApi: SyncApiContract = mock()
                    val bookIngestPort: BookIngestPort = mock()

                    everySuspend { bookIngestPort.upsertWithAudioFiles(any(), any()) } returns
                        failureOf("persistence error")

                    everySuspend { syncApi.getBook(any()) } returns
                        AppResult.Success(
                            bookResponseWithAudioFiles(
                                id = "book-fail",
                                audioFiles =
                                    listOf(
                                        AudioFileResponse(
                                            id = "af-1",
                                            filename = "chapter01.m4b",
                                            format = "m4b",
                                            codec = "aac",
                                            duration = 1_800_000L,
                                            size = 45_000_000L,
                                        ),
                                    ),
                            ),
                        )

                    // ProgressTracker is a final class — use the shared helper from PlaybackManagerTestSupport.
                    val preparer =
                        PlaybackPreparer(
                            serverConfig = mock(),
                            playbackPreferences = mock(),
                            bookDao = db.bookDao(),
                            audioFileDao = db.audioFileDao(),
                            chapterDao = db.chapterDao(),
                            imageStorage = mock(),
                            progressTracker = buildProgressTracker(),
                            tokenProvider = mock(),
                            deviceContext = DeviceContext(type = DeviceType.Phone),
                            downloadService = mock(),
                            playbackRpcFactory = testPlaybackRpcFactory("af-1"),
                            syncApi = syncApi,
                            scope = CoroutineScope(Job()),
                            bookIngestPort = bookIngestPort,
                        )

                    val result = preparer.fetchBookFromServer(BookId("book-fail"))

                    withClue("fetchBookFromServer should return false when persistence fails") { result shouldBe false }
                    verifySuspend(VerifyMode.exactly(1)) {
                        bookIngestPort.upsertWithAudioFiles(any<BookEntity>(), any<List<AudioFileEntity>>())
                    }
                }
            } finally {
                db.close()
            }
        }
    })
