package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class SeriesEditRepositoryOfflineTest :
    FunSpec({
        test("offline series edit persists to Room and enqueues a pending op without ServerConnectError") {
            runTest {
                val db = createInMemoryTestDatabase()
                val seriesId = SeriesId("series1")
                db.seriesDao().upsert(
                    SeriesEntity(
                        id = seriesId,
                        name = "Old Name",
                        description = null,
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
                    SeriesEditRepositoryImpl(
                        channel = RpcChannel.forTest(mock<SeriesService>()),
                        seriesDao = db.seriesDao(),
                        offlineEditor = offlineEditor,
                    )

                val result = repo.updateSeries(seriesId, SeriesUpdate(name = "New Name"))

                result shouldBe AppResult.Success(Unit)
                db.seriesDao().getById(seriesId.value)?.name shouldBe "New Name"
                db
                    .pendingOperationV2Dao()
                    .nextDispatchable(maxAttempts = 5)
                    .firstOrNull()
                    ?.domainName shouldBe "series"
                db.close()
            }
        }
    })
