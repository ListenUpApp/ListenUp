package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.UserPreferencesRpcFactory
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
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
                val authSession: AuthSession = mock()
                everySuspend { authSession.getUserId() } returns "u1"
                val offlineEditor = OfflineEditor(pendingQueue = queue, transactionRunner = txRunner, authSession = authSession)

                // The RPC factory is a bare mock: if the repo tries to push inline, the call throws.
                val repo =
                    UserPreferencesRepositoryImpl(
                        rpcFactory = mock<UserPreferencesRpcFactory>(),
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
