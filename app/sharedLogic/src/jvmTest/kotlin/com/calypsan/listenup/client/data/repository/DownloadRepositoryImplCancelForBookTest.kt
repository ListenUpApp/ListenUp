package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.TierLabels
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Pins that [DownloadRepositoryImpl.cancelForBook] does not report `AppResult.Success` when a
 * per-row `markCancelled` write actually fails (audit finding): the pre-fix code discarded each
 * row's result into `val _`, so a failed row stayed `DOWNLOADING` while the caller was told
 * cancel succeeded — a permanent spinner with no worker behind it (`aggregateBookDownloadStatus`
 * keeps reporting `InProgress`), and no way for the user to retry or dismiss it.
 */
class DownloadRepositoryImplCancelForBookTest :
    FunSpec({
        test("cancelForBook returns Failure when a row's updateState write throws, not silent Success") {
            runTest {
                val rows =
                    listOf(
                        DownloadEntity(
                            audioFileId = "f1",
                            bookId = "b1",
                            filename = "f1.mp3",
                            fileIndex = 0,
                            state = DownloadState.DOWNLOADING,
                            localPath = null,
                            totalBytes = 100L,
                            downloadedBytes = 50L,
                            queuedAt = 0L,
                            startedAt = 0L,
                            completedAt = null,
                            errorMessage = null,
                        ),
                    )
                val dao = FailingUpdateStateDownloadDao(rows = rows, failFor = setOf("f1"))
                val sut =
                    DownloadRepositoryImpl(
                        downloadDao = dao,
                        bookRepository = NoopBookRepository(),
                        enqueuer = NoopEnqueuer(),
                    )

                val result = sut.cancelForBook(BookId("b1"))

                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        test("cancelForBook returns Success when every row's updateState write commits") {
            runTest {
                val rows =
                    listOf(
                        DownloadEntity(
                            audioFileId = "f1",
                            bookId = "b1",
                            filename = "f1.mp3",
                            fileIndex = 0,
                            state = DownloadState.DOWNLOADING,
                            localPath = null,
                            totalBytes = 100L,
                            downloadedBytes = 50L,
                            queuedAt = 0L,
                            startedAt = 0L,
                            completedAt = null,
                            errorMessage = null,
                        ),
                    )
                val dao = FailingUpdateStateDownloadDao(rows = rows, failFor = emptySet())
                val sut =
                    DownloadRepositoryImpl(
                        downloadDao = dao,
                        bookRepository = NoopBookRepository(),
                        enqueuer = NoopEnqueuer(),
                    )

                val result = sut.cancelForBook(BookId("b1"))

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                dao.updatedStates["f1"] shouldBe DownloadState.CANCELLED
            }
        }
    })

/**
 * Hand-rolled fake (not a mock — Testing rubric): [updateState] throws for any audioFileId in
 * [failFor], letting the test force exactly the per-row failure the pre-fix code discarded.
 * Every other member is a minimal stub — [cancelForBook] only calls [getForBook]/[updateState].
 */
private class FailingUpdateStateDownloadDao(
    private val rows: List<DownloadEntity>,
    private val failFor: Set<String>,
) : DownloadDao {
    val updatedStates = mutableMapOf<String, DownloadState>()

    override fun observeForBook(bookId: String): Flow<List<DownloadEntity>> = flowOf(rows.filter { it.bookId == bookId })

    override fun observeAll(): Flow<List<DownloadEntity>> = flowOf(rows)

    override suspend fun getForBook(bookId: String): List<DownloadEntity> = rows.filter { it.bookId == bookId }

    override suspend fun getByAudioFileId(audioFileId: String): DownloadEntity? = rows.firstOrNull { it.audioFileId == audioFileId }

    override suspend fun getIncompleteWithin(maxRetries: Int): List<DownloadEntity> = emptyList()

    override suspend fun getLocalPath(audioFileId: String): String? = null

    override suspend fun insert(download: DownloadEntity) = error("not needed by cancelForBook")

    override suspend fun insertAll(downloads: List<DownloadEntity>) = error("not needed by cancelForBook")

    override suspend fun updateState(
        audioFileId: String,
        state: DownloadState,
        startedAt: Long?,
    ) {
        if (audioFileId in failFor) error("simulated DB failure updating $audioFileId")
        updatedStates[audioFileId] = state
    }

    override suspend fun markPausedIfNotTerminal(audioFileId: String) = error("not needed by cancelForBook")

    override suspend fun updateStateForBookExcluding(
        bookId: String,
        newState: DownloadState,
        excludeState: DownloadState,
    ) = error("not needed by cancelForBook")

    override suspend fun updateProgress(
        audioFileId: String,
        downloaded: Long,
        total: Long,
    ) = error("not needed by cancelForBook")

    override suspend fun markCompletedWithState(
        audioFileId: String,
        localPath: String,
        completedAt: Long,
        state: DownloadState,
    ) = error("not needed by cancelForBook")

    override suspend fun updateErrorWithState(
        audioFileId: String,
        error: String,
        state: DownloadState,
    ) = error("not needed by cancelForBook")

    override suspend fun markDeletedForBook(bookId: String) = error("not needed by cancelForBook")

    override suspend fun hasDeletedRecords(bookId: String): Boolean = error("not needed by cancelForBook")

    override suspend fun deleteForBook(bookId: String) = error("not needed by cancelForBook")

    override suspend fun deleteDeletedRecordsForBook(bookId: String) = error("not needed by cancelForBook")

    override suspend fun deleteAll() = error("not needed by cancelForBook")
}

private class NoopEnqueuer : DownloadEnqueuer {
    override suspend fun enqueue(entity: DownloadEntity): AppResult<Unit> = AppResult.Success(Unit)
}

private class NoopBookRepository : BookRepository {
    override suspend fun refreshBooks(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun getChapters(bookId: String): List<Chapter> = emptyList()

    override fun observeChapters(bookId: String): Flow<List<Chapter>> = flowOf(emptyList())

    override fun observeBookTierLabels(bookId: String): Flow<TierLabels> = flowOf(TierLabels.None)

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

    /** Not exercised here — these fakes cover read paths, and a delete is a server-only write. */
    override suspend fun deleteBook(id: BookId): AppResult<Unit> = AppResult.Success(Unit)
}
