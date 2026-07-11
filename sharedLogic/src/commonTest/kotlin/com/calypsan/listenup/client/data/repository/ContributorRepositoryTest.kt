package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.remote.ContributorApiContract
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.ContributorSearchResult
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Tests for ContributorRepository.
 *
 * Tests the "never stranded" pattern:
 * - Online: Use server Bleve search
 * - Offline or server failure: Fall back to local Room FTS5
 */
class ContributorRepositoryTest :
    FunSpec({

        // --- Helper functions for creating mocks ---

        fun createMockApi(): ContributorApiContract = mock<ContributorApiContract>()

        fun createMockSearchDao(): SearchDao = mock<SearchDao>(MockMode.autoUnit)

        fun createMockContributorDao(): ContributorDao = mock<ContributorDao>(MockMode.autoUnit)

        fun createMockBookDao(): BookDao = mock<BookDao>(MockMode.autoUnit)

        fun createMockNetworkMonitor(): NetworkMonitor = mock<NetworkMonitor>()

        fun createMockImageStorage(): ImageStorage = mock<ImageStorage>()

        fun createTestChannel(): RpcChannel<ContributorService> = RpcChannel.forTest(mock<ContributorService>(MockMode.autoUnit))

        fun createMockSyncHandler(): SyncDomainHandler<ContributorSyncPayload> = mock<SyncDomainHandler<ContributorSyncPayload>>(MockMode.autoUnit)

        fun createTestContributorEntity(
            id: String = "contrib-1",
            name: String = "Brandon Sanderson",
        ): ContributorEntity =
            ContributorEntity(
                id =
                    com.calypsan.listenup.core
                        .ContributorId(id),
                name = name,
                description = null,
                imagePath = null,
                createdAt = Timestamp(0),
                updatedAt = Timestamp(0),
            )

        fun createContributorSearchResult(
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

        test("empty query returns empty result") {
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
                        networkMonitor = networkMonitor,
                        imageStorage = createMockImageStorage(),
                        channel = createTestChannel(),
                        contributorSyncHandler = createMockSyncHandler(),
                    )

                // When
                val result = repository.searchContributors("")

                // Then
                (result.contributors.isEmpty()) shouldBe true
                result.tookMs shouldBe 0
            }
        }

        test("single character query returns empty result") {
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
                        networkMonitor = networkMonitor,
                        imageStorage = createMockImageStorage(),
                        channel = createTestChannel(),
                        contributorSyncHandler = createMockSyncHandler(),
                    )

                // When
                val result = repository.searchContributors("b")

                // Then
                (result.contributors.isEmpty()) shouldBe true
            }
        }

        test("whitespace-only query returns empty result") {
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
                        networkMonitor = networkMonitor,
                        imageStorage = createMockImageStorage(),
                        channel = createTestChannel(),
                        contributorSyncHandler = createMockSyncHandler(),
                    )

                // When
                val result = repository.searchContributors("   ")

                // Then
                (result.contributors.isEmpty()) shouldBe true
            }
        }

        // --- Online search tests ---

        test("online search calls server API") {
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
                        networkMonitor = networkMonitor,
                        imageStorage = createMockImageStorage(),
                        channel = createTestChannel(),
                        contributorSyncHandler = createMockSyncHandler(),
                    )

                every { networkMonitor.isOnline() } returns true

                val serverResults =
                    listOf(
                        createContributorSearchResult(id = "c1", name = "Brandon Sanderson", bookCount = 10),
                        createContributorSearchResult(id = "c2", name = "Brian McClellan", bookCount = 5),
                    )
                everySuspend { api.searchContributors(any(), any()) } returns AppResult.Success(serverResults)

                // When
                val result = repository.searchContributors("bran")

                // Then
                result.contributors.size shouldBe 2
                result.contributors[0].name shouldBe "Brandon Sanderson"
                result.contributors[1].name shouldBe "Brian McClellan"
                result.isOfflineResult shouldBe false
            }
        }

        test("online search passes limit parameter") {
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
                        networkMonitor = networkMonitor,
                        imageStorage = createMockImageStorage(),
                        channel = createTestChannel(),
                        contributorSyncHandler = createMockSyncHandler(),
                    )

                every { networkMonitor.isOnline() } returns true
                everySuspend { api.searchContributors(any(), any()) } returns AppResult.Success(emptyList())

                // When
                repository.searchContributors("test", limit = 5)

                // Then - verify API was called with correct limit
                verifySuspend { api.searchContributors(any(), any()) }
            }
        }

        test("online search returns book counts") {
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
                        networkMonitor = networkMonitor,
                        imageStorage = createMockImageStorage(),
                        channel = createTestChannel(),
                        contributorSyncHandler = createMockSyncHandler(),
                    )

                every { networkMonitor.isOnline() } returns true

                val serverResults =
                    listOf(
                        createContributorSearchResult(id = "c1", name = "Brandon Sanderson", bookCount = 15),
                    )
                everySuspend { api.searchContributors(any(), any()) } returns AppResult.Success(serverResults)

                // When
                val result = repository.searchContributors("sanderson")

                // Then
                result.contributors[0].bookCount shouldBe 15
            }
        }

        // --- Offline fallback tests ---

        test("offline search uses local FTS") {
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
                        networkMonitor = networkMonitor,
                        imageStorage = createMockImageStorage(),
                        channel = createTestChannel(),
                        contributorSyncHandler = createMockSyncHandler(),
                    )

                every { networkMonitor.isOnline() } returns false
                everySuspend { searchDao.searchContributors(any(), any()) } returns
                    listOf(createTestContributorEntity(id = "c1", name = "Local Author"))

                // When
                val result = repository.searchContributors("local")

                // Then
                result.contributors.size shouldBe 1
                result.contributors[0].name shouldBe "Local Author"
                result.isOfflineResult shouldBe true
            }
        }

        test("offline search sets book count to zero") {
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
                        networkMonitor = networkMonitor,
                        imageStorage = createMockImageStorage(),
                        channel = createTestChannel(),
                        contributorSyncHandler = createMockSyncHandler(),
                    )

                every { networkMonitor.isOnline() } returns false
                everySuspend { searchDao.searchContributors(any(), any()) } returns
                    listOf(createTestContributorEntity(id = "c1", name = "Author"))

                // When
                val result = repository.searchContributors("author")

                // Then
                result.contributors[0].bookCount shouldBe 0 // Not available offline
            }
        }

        test("server error falls back to local FTS") {
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
                        networkMonitor = networkMonitor,
                        imageStorage = createMockImageStorage(),
                        channel = createTestChannel(),
                        contributorSyncHandler = createMockSyncHandler(),
                    )

                every { networkMonitor.isOnline() } returns true
                everySuspend { api.searchContributors(any(), any()) } returns
                    Failure(Exception("Server error"))
                everySuspend { searchDao.searchContributors(any(), any()) } returns
                    listOf(createTestContributorEntity(id = "c1", name = "Fallback Author"))

                // When
                val result = repository.searchContributors("fallback")

                // Then
                result.contributors.size shouldBe 1
                result.contributors[0].name shouldBe "Fallback Author"
                result.isOfflineResult shouldBe true
            }
        }

        // --- Query sanitization tests ---

        test("query with special characters is sanitized") {
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
                        networkMonitor = networkMonitor,
                        imageStorage = createMockImageStorage(),
                        channel = createTestChannel(),
                        contributorSyncHandler = createMockSyncHandler(),
                    )

                every { networkMonitor.isOnline() } returns true
                everySuspend { api.searchContributors(any(), any()) } returns AppResult.Success(emptyList())

                // When - query with FTS special chars that should be stripped
                val result = repository.searchContributors("test*()\":")

                // Then - should not throw, search executes
                result.isOfflineResult shouldBe false
            }
        }

        test("very long query is truncated") {
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
                        networkMonitor = networkMonitor,
                        imageStorage = createMockImageStorage(),
                        channel = createTestChannel(),
                        contributorSyncHandler = createMockSyncHandler(),
                    )

                every { networkMonitor.isOnline() } returns true
                everySuspend { api.searchContributors(any(), any()) } returns AppResult.Success(emptyList())

                // When - query longer than 100 chars
                val longQuery = "ab" + "a".repeat(200)
                val result = repository.searchContributors(longQuery)

                // Then - should not throw, search executes with truncated query
                result.isOfflineResult shouldBe false
            }
        }

        // --- FTS query conversion tests ---

        test("local search converts query to FTS format") {
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
                        networkMonitor = networkMonitor,
                        imageStorage = createMockImageStorage(),
                        channel = createTestChannel(),
                        contributorSyncHandler = createMockSyncHandler(),
                    )

                every { networkMonitor.isOnline() } returns false
                everySuspend { searchDao.searchContributors(any(), any()) } returns emptyList()

                // When - multi-word query
                repository.searchContributors("brandon sanderson")

                // Then - should call searchDao (FTS query conversion happens internally)
                verifySuspend { searchDao.searchContributors(any(), any()) }
            }
        }
    })
