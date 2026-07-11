package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class ContributorEditRepositoryOfflineTest :
    FunSpec({
        test("offline contributor edit persists to Room and enqueues a pending op without ServerConnectError") {
            runTest {
                val db = createInMemoryTestDatabase()
                val contributorId = ContributorId("c1")
                db.contributorDao().upsert(
                    ContributorEntity(
                        id = contributorId,
                        name = "Old Name",
                        description = null,
                        imagePath = null,
                        createdAt = Timestamp(0L),
                        updatedAt = Timestamp(0L),
                    ),
                )

                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val txRunner =
                    object : TransactionRunner {
                        override suspend fun <R> atomically(block: suspend () -> R): R = block()
                    }
                val offlineEditor =
                    OfflineEditor(
                        pendingQueue = queue,
                        transactionRunner = txRunner,
                        authSession = FakeAuthSession(userId = "u1"),
                    )

                val repo =
                    ContributorEditRepositoryImpl(
                        channel = RpcChannel.forTest(mock<ContributorService>()),
                        contributorDao = db.contributorDao(),
                        offlineEditor = offlineEditor,
                    )

                val result = repo.updateContributor(contributorId, ContributorUpdate(name = "New Name"))

                result shouldBe AppResult.Success(Unit)
                db.contributorDao().getById(contributorId.value)?.name shouldBe "New Name"
                db
                    .pendingOperationV2Dao()
                    .nextDispatchable(maxAttempts = 5)
                    .firstOrNull()
                    ?.domainName shouldBe "contributors"
                db.close()
            }
        }
    })
