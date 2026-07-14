package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class UserPreferencesOfflineTest :
    FunSpec({
        test("setting a preference offline writes Room and enqueues without an inline RPC") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val txRunner =
                    object : TransactionRunner {
                        override suspend fun <R> atomically(block: suspend () -> R): R = block()
                    }
                val authSession = FakeAuthSession(userId = "u1")
                val offlineEditor = OfflineEditor(pendingQueue = queue, transactionRunner = txRunner, authSession = authSession)

                // The channel wraps a bare mock service: if the repo tries to push inline, the
                // unstubbed call folds to a Failure and the Success assertion below would fail.
                val repo =
                    UserPreferencesRepositoryImpl(
                        channel = RpcChannel.forTest(mock<UserPreferencesService>()),
                        dao = db.userPreferencesDao(),
                        authSession = authSession,
                        offlineEditor = offlineEditor,
                    )

                val result = repo.setDefaultPlaybackSpeed(1.5f)

                result shouldBe AppResult.Success(Unit)
                db.userPreferencesDao().get("u1")?.defaultPlaybackSpeed shouldBe 1.5f
                db
                    .pendingOperationV2Dao()
                    .nextDispatchable(maxAttempts = 5)
                    .firstOrNull()
                    ?.domainName shouldBe "preferences"
                db.close()
            }
        }
    })
