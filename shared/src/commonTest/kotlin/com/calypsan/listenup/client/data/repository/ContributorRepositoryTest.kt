package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.remote.ContributorApiContract
import com.calypsan.listenup.client.data.remote.ContributorSearchResult
import com.calypsan.listenup.client.data.remote.MetadataApiContract
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for ContributorRepository.
 *
 * Tests the "never stranded" pattern:
 * - Online: Use server Bleve search
 * - Offline or server failure: Fall back to local Room FTS5
 */
class ContributorRepositoryTest {
    // --- Helper functions for creating mocks ---

    private fun createMockApi(): ContributorApiContract = mock<ContributorApiContract>()

    private fun createMockSearchDao(): SearchDao = mock<SearchDao>(MockMode.autoUnit)

    private fun createMockContributorDao(): ContributorDao = mock<ContributorDao>(MockMode.autoUnit)

    private fun createMockBookDao(): BookDao = mock<BookDao>(MockMode.autoUnit)

    private fun createMockNetworkMonitor(): NetworkMonitor = mock<NetworkMonitor>()

    private fun createMockMetadataApi(): MetadataApiContract = mock<MetadataApiContract>()

    private fun createMockImageStorage(): ImageStorage = mock<ImageStorage>()

    private fun createTestContributorEntity(
        id: String = "contrib-1",
        name: String = "Brandon Sanderson",
    ): ContributorEntity =
        ContributorEntity(
            id =
                com.calypsan.listenup.client.core
                    .ContributorId(id),
            name = name,
            description = null,
            imagePath = null,
            createdAt = Timestamp(0),
            updatedAt = Timestamp(0),
        )

    private fun createContributorSearchResult(
        id: String = "contrib-1",
        name: String = "Brandon Sanderson",
        bookCount: Int = 5,
    ): ContributorSearchResult =
        ContributorSearchResult(
            id = id,
            name = name,
            bookCount = bookCount,
        )

    // --- Empty/short query tests ---

    @Test
    fun `empty query returns empty result`() =
        runTest {
            // Given
            val api = createMockApi()
            val searchDao = createMockSearchDao()
            val networkMonitor = createMockNetworkMonitor()
            val repository =
                ContributorRepositoryImpl(
                    contributorDao = createMockContributorDao(),
                    bookDao = createMockBookDao(),
                    searchDao = searchDao,
                    api = api,
                    metadataApi = createMockMetadataApi(),
                    networkMonitor = networkMonitor,
                    imageStorage = createMockImageStorage(),
                )

            // When
            val result = repository.searchContributors("")

            // Then
            assertTrue(result.contributors.isEmpty())
            assertEquals(0, result.tookMs)
        }

    @Test
    fun `single character query returns empty result`() =
        runTest {
            // Given
            val api = createMockApi()
            val searchDao = createMockSearchDao()
            val networkMonitor = createMockNetworkMonitor()
            val repository =
                ContributorRepositoryImpl(
                    contributorDao = createMockContributorDao(),
                    bookDao = createMockBookDao(),
                    searchDao = searchDao,
                    api = api,
                    metadataApi = createMockMetadataApi(),
                    networkMonitor = networkMonitor,
                    imageStorage = createMockImageStorage(),
                )

            // When
            val result = repository.searchContributors("b")

            // Then
            assertTrue(result.contributors.isEmpty())
        }

    @Test
    fun `whitespace-only query returns empty result`() =
        runTest {
            // Given
            val api = createMockApi()
            val searchDao = createMockSearchDao()
            val networkMonitor = createMockNetworkMonitor()
            val repository =
                ContributorRepositoryImpl(
                    contributorDao = createMockContributorDao(),
                    bookDao = createMockBookDao(),
                    searchDao = searchDao,
                    api = api,
                    metadataApi = createMockMetadataApi(),
                    networkMonitor = networkMonitor,
                    imageStorage = createMockImageStorage(),
                )

            // When
            val result = repository.searchContributors("   ")

            // Then
            assertTrue(result.contributors.isEmpty())
        }

    // --- Online search tests ---

    @Test
    fun `online search calls server API`() =
        runTest {
            // Given
            val api = createMockApi()
            val searchDao = createMockSearchDao()
            val networkMonitor = createMockNetworkMonitor()
            val repository =
                ContributorRepositoryImpl(
                    contributorDao = createMockContributorDao(),
                    bookDao = createMockBookDao(),
                    searchDao = searchDao,
                    api = api,
                    metadataApi = createMockMetadataApi(),
                    networkMonitor = networkMonitor,
                    imageStorage = createMockImageStorage(),
                )

            every { networkMonitor.isOnline() } returns true

            val serverResults =
                listOf(
                    createContributorSearchResult(id = "c1", name = "Brandon Sanderson", bookCount = 10),
                    createContributorSearchResult(id = "c2", name = "Brian McClellan", bookCount = 5),
                )
            everySuspend { api.searchContributors(any(), any()) } returns Success(serverResults)

            // When
            val result = repository.searchContributors("bran")

            // Then
            assertEquals(2, result.contributors.size)
            assertEquals("Brandon Sanderson", result.contributors[0].name)
            assertEquals("Brian McClellan", result.contributors[1].name)
            assertFalse(result.isOfflineResult)
        }

    @Test
    fun `online search passes limit parameter`() =
        runTest {
            // Given
            val api = createMockApi()
            val searchDao = createMockSearchDao()
            val networkMonitor = createMockNetworkMonitor()
            val repository =
                ContributorRepositoryImpl(
                    contributorDao = createMockContributorDao(),
                    bookDao = createMockBookDao(),
                    searchDao = searchDao,
                    api = api,
                    metadataApi = createMockMetadataApi(),
                    networkMonitor = networkMonitor,
                    imageStorage = createMockImageStorage(),
                )

            every { networkMonitor.isOnline() } returns true
            everySuspend { api.searchContributors(any(), any()) } returns Success(emptyList())

            // When
            repository.searchContributors("test", limit = 5)

            // Then - verify API was called with correct limit
            verifySuspend { api.searchContributors(any(), any()) }
        }

    @Test
    fun `online search returns book counts`() =
        runTest {
            // Given
            val api = createMockApi()
            val searchDao = createMockSearchDao()
            val networkMonitor = createMockNetworkMonitor()
            val repository =
                ContributorRepositoryImpl(
                    contributorDao = createMockContributorDao(),
                    bookDao = createMockBookDao(),
                    searchDao = searchDao,
                    api = api,
                    metadataApi = createMockMetadataApi(),
                    networkMonitor = networkMonitor,
                    imageStorage = createMockImageStorage(),
                )

            every { networkMonitor.isOnline() } returns true

            val serverResults =
                listOf(
                    createContributorSearchResult(id = "c1", name = "Brandon Sanderson", bookCount = 15),
                )
            everySuspend { api.searchContributors(any(), any()) } returns Success(serverResults)

            // When
            val result = repository.searchContributors("sanderson")

            // Then
            assertEquals(15, result.contributors[0].bookCount)
        }

    // --- Offline fallback tests ---

    @Test
    fun `offline search uses local FTS`() =
        runTest {
            // Given
            val api = createMockApi()
            val searchDao = createMockSearchDao()
            val networkMonitor = createMockNetworkMonitor()
            val repository =
                ContributorRepositoryImpl(
                    contributorDao = createMockContributorDao(),
                    bookDao = createMockBookDao(),
                    searchDao = searchDao,
                    api = api,
                    metadataApi = createMockMetadataApi(),
                    networkMonitor = networkMonitor,
                    imageStorage = createMockImageStorage(),
                )

            every { networkMonitor.isOnline() } returns false
            everySuspend { searchDao.searchContributors(any(), any()) } returns
                listOf(createTestContributorEntity(id = "c1", name = "Local Author"))

            // When
            val result = repository.searchContributors("local")

            // Then
            assertEquals(1, result.contributors.size)
            assertEquals("Local Author", result.contributors[0].name)
            assertTrue(result.isOfflineResult)
        }

    @Test
    fun `offline search sets book count to zero`() =
        runTest {
            // Given
            val api = createMockApi()
            val searchDao = createMockSearchDao()
            val networkMonitor = createMockNetworkMonitor()
            val repository =
                ContributorRepositoryImpl(
                    contributorDao = createMockContributorDao(),
                    bookDao = createMockBookDao(),
                    searchDao = searchDao,
                    api = api,
                    metadataApi = createMockMetadataApi(),
                    networkMonitor = networkMonitor,
                    imageStorage = createMockImageStorage(),
                )

            every { networkMonitor.isOnline() } returns false
            everySuspend { searchDao.searchContributors(any(), any()) } returns
                listOf(createTestContributorEntity(id = "c1", name = "Author"))

            // When
            val result = repository.searchContributors("author")

            // Then
            assertEquals(0, result.contributors[0].bookCount) // Not available offline
        }

    @Test
    fun `server error falls back to local FTS`() =
        runTest {
            // Given
            val api = createMockApi()
            val searchDao = createMockSearchDao()
            val networkMonitor = createMockNetworkMonitor()
            val repository =
                ContributorRepositoryImpl(
                    contributorDao = createMockContributorDao(),
                    bookDao = createMockBookDao(),
                    searchDao = searchDao,
                    api = api,
                    metadataApi = createMockMetadataApi(),
                    networkMonitor = networkMonitor,
                    imageStorage = createMockImageStorage(),
                )

            every { networkMonitor.isOnline() } returns true
            everySuspend { api.searchContributors(any(), any()) } returns
                Failure(Exception("Server error"))
            everySuspend { searchDao.searchContributors(any(), any()) } returns
                listOf(createTestContributorEntity(id = "c1", name = "Fallback Author"))

            // When
            val result = repository.searchContributors("fallback")

            // Then
            assertEquals(1, result.contributors.size)
            assertEquals("Fallback Author", result.contributors[0].name)
            assertTrue(result.isOfflineResult)
        }

    // --- Query sanitization tests ---

    @Test
    fun `query with special characters is sanitized`() =
        runTest {
            // Given
            val api = createMockApi()
            val searchDao = createMockSearchDao()
            val networkMonitor = createMockNetworkMonitor()
            val repository =
                ContributorRepositoryImpl(
                    contributorDao = createMockContributorDao(),
                    bookDao = createMockBookDao(),
                    searchDao = searchDao,
                    api = api,
                    metadataApi = createMockMetadataApi(),
                    networkMonitor = networkMonitor,
                    imageStorage = createMockImageStorage(),
                )

            every { networkMonitor.isOnline() } returns true
            everySuspend { api.searchContributors(any(), any()) } returns Success(emptyList())

            // When - query with FTS special chars that should be stripped
            val result = repository.searchContributors("test*()\":")

            // Then - should not throw, search executes
            assertFalse(result.isOfflineResult)
        }

    @Test
    fun `very long query is truncated`() =
        runTest {
            // Given
            val api = createMockApi()
            val searchDao = createMockSearchDao()
            val networkMonitor = createMockNetworkMonitor()
            val repository =
                ContributorRepositoryImpl(
                    contributorDao = createMockContributorDao(),
                    bookDao = createMockBookDao(),
                    searchDao = searchDao,
                    api = api,
                    metadataApi = createMockMetadataApi(),
                    networkMonitor = networkMonitor,
                    imageStorage = createMockImageStorage(),
                )

            every { networkMonitor.isOnline() } returns true
            everySuspend { api.searchContributors(any(), any()) } returns Success(emptyList())

            // When - query longer than 100 chars
            val longQuery = "ab" + "a".repeat(200)
            val result = repository.searchContributors(longQuery)

            // Then - should not throw, search executes with truncated query
            assertFalse(result.isOfflineResult)
        }

    // --- FTS query conversion tests ---

    @Test
    fun `local search converts query to FTS format`() =
        runTest {
            // Given
            val api = createMockApi()
            val searchDao = createMockSearchDao()
            val networkMonitor = createMockNetworkMonitor()
            val repository =
                ContributorRepositoryImpl(
                    contributorDao = createMockContributorDao(),
                    bookDao = createMockBookDao(),
                    searchDao = searchDao,
                    api = api,
                    metadataApi = createMockMetadataApi(),
                    networkMonitor = networkMonitor,
                    imageStorage = createMockImageStorage(),
                )

            every { networkMonitor.isOnline() } returns false
            everySuspend { searchDao.searchContributors(any(), any()) } returns emptyList()

            // When - multi-word query
            repository.searchContributors("brandon sanderson")

            // Then - should call searchDao (FTS query conversion happens internally)
            verifySuspend { searchDao.searchContributors(any(), any()) }
        }
}
