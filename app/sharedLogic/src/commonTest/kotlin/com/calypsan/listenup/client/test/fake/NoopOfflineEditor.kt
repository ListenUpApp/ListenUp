package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import dev.mokkery.mock

/**
 * A constructible [OfflineEditor] for repository tests that DON'T exercise offline mutation — its
 * queue is backed by a mock DAO and a no-op sender, so it satisfies the constructor without any
 * durable side effects. [OfflineEditor] is a final class (it cannot be mocked); tests that assert
 * enqueue behaviour build a real editor over an in-memory database instead.
 */
internal fun noopOfflineEditor(): OfflineEditor =
    OfflineEditor(
        pendingQueue = PendingOperationQueue(dao = mock(), sender = PendingOperationSender { AppResult.Success(Unit) }),
        transactionRunner =
            object : TransactionRunner {
                override suspend fun <R> atomically(block: suspend () -> R): R = block()
            },
        authSession = FakeAuthSession(userId = "test-user"),
    )
