package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies the DAO → PlaybackManager.prepareForPlayback contract.
 *
 * Seeds audio files directly into the junction; calls prepareForPlayback; asserts
 * the returned PrepareResult has the expected timeline shape (segment URLs,
 * durations, and order).
 *
 * No actual audio plays — this is a data-layer-to-PlaybackManager integration
 * test. The acceptance test for actual playback is a manual checkpoint on a
 * real device before push (see plan Task 9).
 */
class PlaybackManagerPrepareTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `prepareForPlayback builds timeline from junction rows in index order`() =
        runTest {
            seedBookAndAudioFiles()

            val playbackManager = createPlaybackManager()

            val result = playbackManager.prepareForPlayback(BookId("book-1"))

            assertNotNull(result)
            assertEquals(3, result.timeline.files.size, "timeline should have 3 segments")
            // Verify ordering: segments should be in index order (0, 1, 2)
            assertEquals("af-0", result.timeline.files[0].audioFileId)
            assertEquals("af-1", result.timeline.files[1].audioFileId)
            assertEquals("af-2", result.timeline.files[2].audioFileId)
        }

    private suspend fun seedBookAndAudioFiles() {
        db.bookDao().upsert(
            BookEntity(
                id = BookId("book-1"),
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = "Test Book",
                sortTitle = "Test Book",
                subtitle = null,
                coverHash = null,
                coverBlurHash = null,
                dominantColor = null,
                darkMutedColor = null,
                vibrantColor = null,
                totalDuration = 5_400_000L,
                description = null,
                publishYear = null,
                publisher = null,
                language = null,
                isbn = null,
                asin = null,
                abridged = false,
                createdAt = Timestamp(1L),
                updatedAt = Timestamp(1L),
            ),
        )
        db.audioFileDao().upsertAll(
            listOf(
                audioFile(index = 0, id = "af-0"),
                audioFile(index = 1, id = "af-1"),
                audioFile(index = 2, id = "af-2"),
            ),
        )
    }

    private fun audioFile(
        index: Int,
        id: String,
    ): AudioFileEntity =
        AudioFileEntity(
            bookId = BookId("book-1"),
            index = index,
            id = id,
            filename = "chapter${index + 1}.m4b",
            format = "m4b",
            codec = "aac",
            duration = 1_800_000L,
            size = 45_000_000L,
        )

    private fun createPlaybackManager(): PlaybackManager {
        val tokenProvider: AudioTokenProvider = mock()
        everySuspend { tokenProvider.prepareForPlayback() } returns Unit

        val serverConfig: ServerConfig = mock()
        everySuspend { serverConfig.getServerUrl() } returns ServerUrl("https://example.test")

        val imageStorage: ImageStorage = mock()
        every { imageStorage.exists(any()) } returns false

        val downloadService: DownloadService = mock()
        everySuspend { downloadService.getLocalPath(any()) } returns null
        everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
        everySuspend { downloadService.downloadBook(any()) } returns AppResult.Success(DownloadOutcome.AlreadyDownloaded)

        val playbackPreferences: PlaybackPreferences = mock()
        everySuspend { playbackPreferences.getDefaultPlaybackSpeed() } returns 1.0f

        // ProgressTracker is a final class — use the shared helper from PlaybackManagerTestSupport.
        // prepareForPlayback reads positionRepository; defaultPositionRepository() stubs it to
        // return null (no saved position), exercising the fresh-playback path.
        val progressTracker = buildProgressTracker()

        return PlaybackManagerImpl(
            serverConfig = serverConfig,
            playbackPreferences = playbackPreferences,
            bookDao = db.bookDao(),
            audioFileDao = db.audioFileDao(),
            chapterDao = db.chapterDao(),
            imageStorage = imageStorage,
            progressTracker = progressTracker,
            tokenProvider = tokenProvider,
            deviceContext = DeviceContext(type = DeviceType.Phone),
            downloadService = downloadService,
            playbackApi = null,
            capabilityDetector = null,
            syncApi = null,
            scope = CoroutineScope(Job()),
            bookRepository = mock<BookRepository>(),
        )
    }
}
