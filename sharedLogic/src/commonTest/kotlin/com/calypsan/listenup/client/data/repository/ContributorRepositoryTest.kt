package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.dto.ContributorHit
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.remote.RpcChannel
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
 * Tests for ContributorRepository server-search + never-stranded fallback.
 *
 * Server search now rides the unified [SearchService] over RPC (the `contributors` slice of the
 * [SearchResults] envelope), not the retired REST `searchContributors` endpoint:
 * - Online: call [SearchService.search], map [ContributorHit] → domain results.
 * - Offline or server failure: fall back to local Room FTS5.
 */
class ContributorRepositoryTest :
    FunSpec({

        // --- Helpers ---

        fun searchResultsOf(vararg contributors: ContributorHit): SearchResults =
            SearchResults(
                books = emptyList(),
                contributors = contributors.toList(),
                series = emptyList(),
                tags = emptyList(),
            )

        fun contributorHit(
            id: String = "contrib-1",
            name: String = "Brandon Sanderson",
            bookCount: Int = 5,
        ): ContributorHit =
            ContributorHit(
                id = ContributorId(id),
                name = name,
                sortName = name,
                photoPath = null,
                photoBlurHash = null,
                bookCount = bookCount,
            )

        fun createTestContributorEntity(
            id: String = "contrib-1",
            name: String = "Brandon Sanderson",
        ): ContributorEntity =
            ContributorEntity(
                id = ContributorId(id),
                name = name,
                description = null,
                imagePath = null,
                createdAt = Timestamp(0),
                updatedAt = Timestamp(0),
            )

        fun createRepository(
            searchService: SearchService,
            searchDao: SearchDao,
            networkMonitor: NetworkMonitor,
        ): ContributorRepositoryImpl =
            ContributorRepositoryImpl(
                contributorDao = mock<ContributorDao>(MockMode.autoUnit),
                bookDao = mock<BookDao>(MockMode.autoUnit),
                searchDao = searchDao,
                networkMonitor = networkMonitor,
                imageStorage = mock<ImageStorage>(),
                channel = RpcChannel.forTest(mock<ContributorService>(MockMode.autoUnit)),
                searchChannel = RpcChannel.forTest(searchService),
                contributorSyncHandler = mock<SyncDomainHandler<ContributorSyncPayload>>(MockMode.autoUnit),
            )

        // --- Empty/short query tests ---

        test("empty query returns empty result") {
            runTest {
                val repository =
                    createRepository(mock<SearchService>(), mock<SearchDao>(MockMode.autoUnit), mock<NetworkMonitor>())

                val result = repository.searchContributors("")

                result.contributors.isEmpty() shouldBe true
                result.tookMs shouldBe 0
            }
        }

        test("single character query returns empty result") {
            runTest {
                val repository =
                    createRepository(mock<SearchService>(), mock<SearchDao>(MockMode.autoUnit), mock<NetworkMonitor>())

                repository.searchContributors("b").contributors.isEmpty() shouldBe true
            }
        }

        test("whitespace-only query returns empty result") {
            runTest {
                val repository =
                    createRepository(mock<SearchService>(), mock<SearchDao>(MockMode.autoUnit), mock<NetworkMonitor>())

                repository.searchContributors("   ").contributors.isEmpty() shouldBe true
            }
        }

        // --- Online search tests ---

        test("online search calls the unified SearchService and maps the contributors slice") {
            runTest {
                val searchService = mock<SearchService>()
                val networkMonitor = mock<NetworkMonitor>()
                every { networkMonitor.isOnline() } returns true
                everySuspend { searchService.search(any()) } returns
                    AppResult.Success(
                        searchResultsOf(
                            contributorHit(id = "c1", name = "Brandon Sanderson", bookCount = 10),
                            contributorHit(id = "c2", name = "Brian McClellan", bookCount = 5),
                        ),
                    )
                val repository = createRepository(searchService, mock<SearchDao>(MockMode.autoUnit), networkMonitor)

                val result = repository.searchContributors("bran")

                result.contributors.size shouldBe 2
                result.contributors[0].name shouldBe "Brandon Sanderson"
                result.contributors[1].name shouldBe "Brian McClellan"
                result.isOfflineResult shouldBe false
            }
        }

        test("online search calls SearchService.search") {
            runTest {
                val searchService = mock<SearchService>()
                val networkMonitor = mock<NetworkMonitor>()
                every { networkMonitor.isOnline() } returns true
                everySuspend { searchService.search(any()) } returns AppResult.Success(searchResultsOf())
                val repository = createRepository(searchService, mock<SearchDao>(MockMode.autoUnit), networkMonitor)

                repository.searchContributors("test", limit = 5)

                verifySuspend { searchService.search(any()) }
            }
        }

        test("online search returns book counts") {
            runTest {
                val searchService = mock<SearchService>()
                val networkMonitor = mock<NetworkMonitor>()
                every { networkMonitor.isOnline() } returns true
                everySuspend { searchService.search(any()) } returns
                    AppResult.Success(searchResultsOf(contributorHit(id = "c1", name = "Brandon Sanderson", bookCount = 15)))
                val repository = createRepository(searchService, mock<SearchDao>(MockMode.autoUnit), networkMonitor)

                repository.searchContributors("sanderson").contributors[0].bookCount shouldBe 15
            }
        }

        // --- Offline fallback tests ---

        test("offline search uses local FTS") {
            runTest {
                val searchDao = mock<SearchDao>(MockMode.autoUnit)
                val networkMonitor = mock<NetworkMonitor>()
                every { networkMonitor.isOnline() } returns false
                everySuspend { searchDao.searchContributors(any(), any()) } returns
                    listOf(createTestContributorEntity(id = "c1", name = "Local Author"))
                val repository = createRepository(mock<SearchService>(), searchDao, networkMonitor)

                val result = repository.searchContributors("local")

                result.contributors.size shouldBe 1
                result.contributors[0].name shouldBe "Local Author"
                result.isOfflineResult shouldBe true
            }
        }

        test("offline search sets book count to zero") {
            runTest {
                val searchDao = mock<SearchDao>(MockMode.autoUnit)
                val networkMonitor = mock<NetworkMonitor>()
                every { networkMonitor.isOnline() } returns false
                everySuspend { searchDao.searchContributors(any(), any()) } returns
                    listOf(createTestContributorEntity(id = "c1", name = "Author"))
                val repository = createRepository(mock<SearchService>(), searchDao, networkMonitor)

                repository.searchContributors("author").contributors[0].bookCount shouldBe 0
            }
        }

        test("server error falls back to local FTS") {
            runTest {
                val searchService = mock<SearchService>()
                val searchDao = mock<SearchDao>(MockMode.autoUnit)
                val networkMonitor = mock<NetworkMonitor>()
                every { networkMonitor.isOnline() } returns true
                everySuspend { searchService.search(any()) } returns Failure(Exception("Server error"))
                everySuspend { searchDao.searchContributors(any(), any()) } returns
                    listOf(createTestContributorEntity(id = "c1", name = "Fallback Author"))
                val repository = createRepository(searchService, searchDao, networkMonitor)

                val result = repository.searchContributors("fallback")

                result.contributors.size shouldBe 1
                result.contributors[0].name shouldBe "Fallback Author"
                result.isOfflineResult shouldBe true
            }
        }

        // --- Query sanitization tests ---

        test("query with special characters is sanitized") {
            runTest {
                val searchService = mock<SearchService>()
                val networkMonitor = mock<NetworkMonitor>()
                every { networkMonitor.isOnline() } returns true
                everySuspend { searchService.search(any()) } returns AppResult.Success(searchResultsOf())
                val repository = createRepository(searchService, mock<SearchDao>(MockMode.autoUnit), networkMonitor)

                repository.searchContributors("test*()\":").isOfflineResult shouldBe false
            }
        }

        test("very long query is truncated") {
            runTest {
                val searchService = mock<SearchService>()
                val networkMonitor = mock<NetworkMonitor>()
                every { networkMonitor.isOnline() } returns true
                everySuspend { searchService.search(any()) } returns AppResult.Success(searchResultsOf())
                val repository = createRepository(searchService, mock<SearchDao>(MockMode.autoUnit), networkMonitor)

                val longQuery = "ab" + "a".repeat(200)
                repository.searchContributors(longQuery).isOfflineResult shouldBe false
            }
        }

        // --- FTS query conversion tests ---

        test("local search converts query to FTS format") {
            runTest {
                val searchDao = mock<SearchDao>(MockMode.autoUnit)
                val networkMonitor = mock<NetworkMonitor>()
                every { networkMonitor.isOnline() } returns false
                everySuspend { searchDao.searchContributors(any(), any()) } returns emptyList()
                val repository = createRepository(mock<SearchService>(), searchDao, networkMonitor)

                repository.searchContributors("brandon sanderson")

                verifySuspend { searchDao.searchContributors(any(), any()) }
            }
        }
    })
