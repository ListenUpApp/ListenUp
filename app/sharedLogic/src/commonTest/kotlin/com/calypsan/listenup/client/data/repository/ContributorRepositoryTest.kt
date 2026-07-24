package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.sync.ContributorSyncPayload
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
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Tests for [ContributorRepositoryImpl.searchContributors].
 *
 * Contributor search is LOCAL-ONLY: the client mirrors every contributor in Room and maintains its
 * own FTS5 index, so a search never hits the network — it always routes through
 * [SearchDao.searchContributors] over the local index (Room is the single source of truth).
 */
class ContributorRepositoryTest :
    FunSpec({

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

        fun createRepository(searchDao: SearchDao): ContributorRepositoryImpl =
            ContributorRepositoryImpl(
                contributorDao = mock<ContributorDao>(MockMode.autoUnit),
                bookDao = mock<BookDao>(MockMode.autoUnit),
                searchDao = searchDao,
                networkMonitor = mock<NetworkMonitor>(MockMode.autoUnit),
                imageStorage = mock<ImageStorage>(),
                channel = RpcChannel.forTest(mock<ContributorService>(MockMode.autoUnit)),
                contributorSyncHandler = mock<SyncDomainHandler<ContributorSyncPayload>>(MockMode.autoUnit),
            )

        // --- Empty/short query gate (no DB hit) ---

        test("empty query returns empty result") {
            runTest {
                val result = createRepository(mock<SearchDao>(MockMode.autoUnit)).searchContributors("")
                result.contributors.isEmpty() shouldBe true
                result.tookMs shouldBe 0
            }
        }

        test("single-character query returns empty result") {
            runTest {
                createRepository(mock<SearchDao>(MockMode.autoUnit)).searchContributors("b").contributors.isEmpty() shouldBe true
            }
        }

        test("whitespace-only query returns empty result") {
            runTest {
                createRepository(mock<SearchDao>(MockMode.autoUnit)).searchContributors("   ").contributors.isEmpty() shouldBe true
            }
        }

        // --- Local FTS search ---

        test("search maps local FTS rows to domain results") {
            runTest {
                val searchDao = mock<SearchDao>(MockMode.autoUnit)
                everySuspend { searchDao.searchContributors(any(), any()) } returns
                    listOf(createTestContributorEntity(id = "c1", name = "Local Author"))

                val result = createRepository(searchDao).searchContributors("local")

                result.contributors.size shouldBe 1
                result.contributors[0].name shouldBe "Local Author"
            }
        }

        test("local search reports a zero book count (counts aren't carried by the FTS row)") {
            runTest {
                val searchDao = mock<SearchDao>(MockMode.autoUnit)
                everySuspend { searchDao.searchContributors(any(), any()) } returns
                    listOf(createTestContributorEntity(id = "c1", name = "Author"))

                createRepository(searchDao).searchContributors("author").contributors[0].bookCount shouldBe 0
            }
        }

        test("a query above the minimum length routes to the local FTS DAO") {
            runTest {
                val searchDao = mock<SearchDao>(MockMode.autoUnit)
                everySuspend { searchDao.searchContributors(any(), any()) } returns emptyList()

                createRepository(searchDao).searchContributors("brandon sanderson")

                verifySuspend { searchDao.searchContributors(any(), any()) }
            }
        }

        test("a query with FTS special characters is sanitized and still searches locally") {
            runTest {
                val searchDao = mock<SearchDao>(MockMode.autoUnit)
                everySuspend { searchDao.searchContributors(any(), any()) } returns emptyList()

                createRepository(searchDao).searchContributors("test*()\":")

                verifySuspend { searchDao.searchContributors(any(), any()) }
            }
        }

        test("a very long query is truncated and still searches locally") {
            runTest {
                val searchDao = mock<SearchDao>(MockMode.autoUnit)
                everySuspend { searchDao.searchContributors(any(), any()) } returns emptyList()

                createRepository(searchDao).searchContributors("ab" + "a".repeat(200))

                verifySuspend { searchDao.searchContributors(any(), any()) }
            }
        }
    })
