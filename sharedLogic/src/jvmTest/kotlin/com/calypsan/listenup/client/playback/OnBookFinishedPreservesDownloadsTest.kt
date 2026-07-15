package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.repository.DownloadRepositoryImpl
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.domain.TierLabels
import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * B1 regression: finishing a book must NOT destroy its offline copy.
 *
 * [ProgressTracker.onBookFinished] previously called `deleteForBook` (DELETE FROM downloads WHERE
 * bookId=…), wiping COMPLETED rows too — so every future play fell to streaming and failed offline,
 * while the multi-GB files leaked on disk. It must clear only DELETED tombstones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnBookFinishedPreservesDownloadsTest :
    FunSpec({
        fun completed(
            id: String,
            bookId: String,
        ) = DownloadEntity(
            audioFileId = id,
            bookId = bookId,
            filename = "$id.mp3",
            fileIndex = 0,
            state = DownloadState.COMPLETED,
            localPath = "/audio/$id.mp3",
            totalBytes = 1_000L,
            downloadedBytes = 1_000L,
            queuedAt = 0L,
            startedAt = null,
            completedAt = 1L,
            errorMessage = null,
            retryCount = 0,
        )

        test("onBookFinished preserves COMPLETED downloads and clears only DELETED tombstones") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                val dispatcher = StandardTestDispatcher()
                runTest(dispatcher) {
                    dao.insertAll(
                        listOf(
                            completed("f1", "b1"),
                            completed("f2", "b1"),
                            completed("stale", "b1").copy(state = DownloadState.DELETED, localPath = null),
                        ),
                    )

                    val downloadRepository =
                        DownloadRepositoryImpl(
                            downloadDao = dao,
                            bookRepository = NoopBookRepository(),
                            enqueuer = NoopEnqueuer(),
                        )
                    val positionRepository: PlaybackPositionRepository = mock()
                    everySuspend { positionRepository.markComplete(any(), any(), any()) } returns AppResult.Success(Unit)

                    val tracker =
                        ProgressTracker(
                            downloadRepository = downloadRepository,
                            positionRepository = positionRepository,
                            scope = CoroutineScope(dispatcher),
                        )

                    tracker.onBookFinished(BookId("b1"), finalPositionMs = 1_000L)
                    advanceUntilIdle()

                    // COMPLETED downloads survive — getLocalPath still resolves, so the book plays offline.
                    downloadRepository.getLocalPath("f1") shouldBe "/audio/f1.mp3"
                    downloadRepository.getLocalPath("f2") shouldBe "/audio/f2.mp3"
                    // Only the DELETED tombstone was cleared.
                    downloadRepository.getStateForAudioFile("stale") shouldBe null
                }
            } finally {
                db.close()
            }
        }
    })

private class NoopEnqueuer : DownloadEnqueuer {
    override suspend fun enqueue(entity: DownloadEntity): AppResult<Unit> = AppResult.Success(Unit)
}

private class NoopBookRepository : BookRepository {
    override suspend fun refreshBooks(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun getChapters(bookId: String): List<Chapter> = emptyList()

    override fun observeChapters(bookId: String): Flow<List<Chapter>> = flowOf(emptyList())

    override fun observeBookTierLabels(bookId: String): Flow<TierLabels> = flowOf(TierLabels(null, null))

    override fun observeIsBookLive(id: String): Flow<Boolean> = flowOf(true)

    override fun observeRandomUnstartedBooks(limit: Int): Flow<List<DiscoveryBook>> = flowOf(emptyList())

    override fun observeRecentlyAddedBooks(limit: Int): Flow<List<DiscoveryBook>> = flowOf(emptyList())

    override fun observeBookListItems(): Flow<List<BookListItem>> = flowOf(emptyList())

    override fun observeBookListItems(ids: List<String>): Flow<List<BookListItem>> = flowOf(emptyList())

    override suspend fun getBookListItem(id: String): BookListItem? = null

    override suspend fun getBookListItems(ids: List<String>): List<BookListItem> = emptyList()

    override fun observeBookDetail(id: String): Flow<BookDetail?> = flowOf(null)

    override fun search(query: String): Flow<List<BookListItem>> = flowOf(emptyList())

    override suspend fun getBookDetail(id: String): BookDetail? = null
}
