package com.calypsan.listenup.client.data.sync.pull

import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.SyncDao
import com.calypsan.listenup.client.data.sync.SyncCoordinator
import com.calypsan.listenup.client.data.sync.model.SyncPhase
import com.calypsan.listenup.client.data.sync.model.SyncStatus
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.failureOf

/**
 * Tests for PullSyncOrchestrator.
 *
 * Tests cover:
 * - Parallel execution of entity pullers
 * - Delta vs full sync based on last sync time
 * - Progress reporting
 * - Retry logic integration
 * - Failure handling and job cancellation
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PullSyncOrchestratorTest {
    private class TestFixture {
        val bookPuller: Puller = mock()
        val seriesPuller: Puller = mock()
        val contributorPuller: Puller = mock()
        val tagPuller: Puller = mock()
        val genrePuller: Puller = mock()
        val shelfPuller: Puller = mock()
        val listeningEventPuller: ListeningEventPullerContract = mock()
        val progressPuller: Puller = mock()
        val activeSessionsPuller: Puller = mock()
        val readingSessionsPuller: Puller = mock()
        val syncDao: SyncDao = mock()
        val syncApi: com.calypsan.listenup.client.data.remote.SyncApiContract = mock()
        val bookDao: BookDao = mock()
        val seriesDao: SeriesDao = mock()
        val contributorDao: ContributorDao = mock()

        // Use real coordinator for simpler testing
        val coordinator = SyncCoordinator()

        init {
            // Default stubs - successful pulls
            everySuspend { bookPuller.pull(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { seriesPuller.pull(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { contributorPuller.pull(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { tagPuller.pull(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { genrePuller.pull(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { shelfPuller.pull(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { listeningEventPuller.pull(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { progressPuller.pull(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { activeSessionsPuller.pull(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { readingSessionsPuller.pull(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { syncDao.getValue(SyncDao.KEY_LAST_SYNC_BOOKS) } returns null
            everySuspend { syncApi.getManifest() } returns
                com.calypsan.listenup.client.core
                    .failureOf("no manifest")
            everySuspend { bookDao.count() } returns 0
            everySuspend { seriesDao.count() } returns 0
            everySuspend { contributorDao.count() } returns 0
        }

        fun build(): PullSyncOrchestrator =
            PullSyncOrchestrator(
                bookPuller = bookPuller,
                seriesPuller = seriesPuller,
                contributorPuller = contributorPuller,
                tagPuller = tagPuller,
                genrePuller = genrePuller,
                shelfPuller = shelfPuller,
                listeningEventPuller = listeningEventPuller,
                progressPuller = progressPuller,
                activeSessionsPuller = activeSessionsPuller,
                readingSessionsPuller = readingSessionsPuller,
                coordinator = coordinator,
                syncApi = syncApi,
                syncDao = syncDao,
                bookDao = bookDao,
                seriesDao = seriesDao,
                contributorDao = contributorDao,
            )
    }

    // ========== Successful Pull Tests ==========

    @Test
    fun `pull calls all four pullers`() =
        runTest {
            // Given
            val fixture = TestFixture()
            val orchestrator = fixture.build()

            // When
            orchestrator.pull {}

            // Then - all pullers called (tags last, after books)
            verifySuspend { fixture.bookPuller.pull(any(), any()) }
            verifySuspend { fixture.seriesPuller.pull(any(), any()) }
            verifySuspend { fixture.contributorPuller.pull(any(), any()) }
            verifySuspend { fixture.tagPuller.pull(any(), any()) }
        }

    @Test
    fun `pull passes null updatedAfter for full sync`() =
        runTest {
            // Given - no previous sync
            val fixture = TestFixture()
            everySuspend { fixture.syncDao.getValue(SyncDao.KEY_LAST_SYNC_BOOKS) } returns null
            val orchestrator = fixture.build()

            // When
            orchestrator.pull {}

            // Then - null passed for full sync
            verifySuspend { fixture.bookPuller.pull(null, any()) }
            verifySuspend { fixture.seriesPuller.pull(null, any()) }
            verifySuspend { fixture.contributorPuller.pull(null, any()) }
            verifySuspend { fixture.tagPuller.pull(null, any()) }
        }

    @Test
    fun `pull passes timestamp for delta sync`() =
        runTest {
            // Given - previous sync exists
            val fixture = TestFixture()
            val lastSync = Timestamp(1704067200000L) // 2024-01-01T00:00:00Z
            everySuspend { fixture.syncDao.getValue(SyncDao.KEY_LAST_SYNC_BOOKS) } returns
                lastSync.epochMillis.toString()
            val orchestrator = fixture.build()

            // When
            orchestrator.pull {}

            // Then - ISO timestamp passed for delta sync
            verifySuspend { fixture.bookPuller.pull(lastSync.toIsoString(), any()) }
            verifySuspend { fixture.seriesPuller.pull(lastSync.toIsoString(), any()) }
            verifySuspend { fixture.contributorPuller.pull(lastSync.toIsoString(), any()) }
            verifySuspend { fixture.tagPuller.pull(lastSync.toIsoString(), any()) }
        }

    // ========== Progress Reporting Tests ==========

    @Test
    fun `pull reports initial progress`() =
        runTest {
            // Given
            val fixture = TestFixture()
            val orchestrator = fixture.build()
            val progressUpdates = mutableListOf<SyncStatus>()

            // When
            orchestrator.pull { progressUpdates.add(it) }

            // Then - first update is FETCHING_METADATA
            assertTrue(progressUpdates.isNotEmpty())
            val first = assertIs<SyncStatus.Progress>(progressUpdates.first())
            assertEquals(SyncPhase.FETCHING_METADATA, first.phase)
        }

    @Test
    fun `pull reports finalizing progress`() =
        runTest {
            // Given
            val fixture = TestFixture()
            val orchestrator = fixture.build()
            val progressUpdates = mutableListOf<SyncStatus>()

            // When
            orchestrator.pull { progressUpdates.add(it) }

            // Then - last update is FINALIZING
            assertTrue(progressUpdates.isNotEmpty())
            val last = assertIs<SyncStatus.Progress>(progressUpdates.last())
            assertEquals(SyncPhase.FINALIZING, last.phase)
        }

    // ========== Failure Handling Tests ==========

    @Test
    fun `pull throws when book puller fails after retries`() =
        runTest {
            // Given
            val fixture = TestFixture()
            everySuspend { fixture.bookPuller.pull(any(), any()) } throws RuntimeException("Book sync failed")
            val orchestrator = fixture.build()

            // When/Then
            assertFailsWith<RuntimeException> {
                orchestrator.pull {}
            }
        }

    @Test
    fun `pull throws when series puller fails after retries`() =
        runTest {
            // Given
            val fixture = TestFixture()
            everySuspend { fixture.seriesPuller.pull(any(), any()) } throws RuntimeException("Series sync failed")
            val orchestrator = fixture.build()

            // When/Then
            assertFailsWith<RuntimeException> {
                orchestrator.pull {}
            }
        }

    @Test
    fun `pull throws when contributor puller fails after retries`() =
        runTest {
            // Given
            val fixture = TestFixture()
            everySuspend { fixture.contributorPuller.pull(any(), any()) } throws RuntimeException("Contributor sync failed")
            val orchestrator = fixture.build()

            // When/Then
            assertFailsWith<RuntimeException> {
                orchestrator.pull {}
            }
        }
}
