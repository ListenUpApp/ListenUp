package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.preferences.UserPreferencesDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Dao
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Entity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.UserPreferencesDao
import com.calypsan.listenup.client.data.local.db.UserPreferencesEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

/**
 * In-memory [UserPreferencesDao] fake — a single-row store keyed by user id, backed by a
 * [MutableStateFlow] so [observe] is genuinely reactive. [upsert] is idempotent: writing an
 * identical row leaves the flow value unchanged, so collectors do not re-emit.
 */
private class FakeUserPreferencesDao : UserPreferencesDao {
    val rows = MutableStateFlow<Map<String, UserPreferencesEntity>>(emptyMap())

    override fun observe(userId: String): Flow<UserPreferencesEntity?> = rows.map { it[userId] }

    override suspend fun get(userId: String): UserPreferencesEntity? = rows.value[userId]

    override suspend fun upsert(preferences: UserPreferencesEntity) {
        rows.value = rows.value + (preferences.id to preferences)
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

/**
 * In-memory [PendingOperationV2Dao] fake — records every enqueued op so a test can assert the
 * outbox routing (`domainName`) and decode the patch actually enqueued for a given setter.
 */
private class FakePendingOperationV2Dao : PendingOperationV2Dao {
    val inserted = mutableListOf<PendingOperationV2Entity>()

    override suspend fun insert(op: PendingOperationV2Entity) {
        inserted += op
    }

    override suspend fun get(clientOpId: String): PendingOperationV2Entity? = inserted.find { it.clientOpId == clientOpId }

    override suspend fun delete(clientOpId: String) {
        inserted.removeAll { it.clientOpId == clientOpId }
    }

    override suspend fun update(op: PendingOperationV2Entity) {
        inserted.replaceAll { if (it.clientOpId == op.clientOpId) op else it }
    }

    override suspend fun nextDispatchable(maxAttempts: Int): List<PendingOperationV2Entity> = inserted.toList()

    override suspend fun countDispatchable(maxAttempts: Int): Int = inserted.count { it.failureCount <= maxAttempts }

    override suspend fun hasQueuedOp(
        domainName: String,
        entityId: String,
        maxAttempts: Int,
    ): Boolean =
        inserted.any {
            it.domainName == domainName && it.entityId == entityId && it.failureCount <= maxAttempts
        }

    override suspend fun deleteQueuedOps(
        domainName: String,
        entityId: String,
        opType: String,
        maxAttempts: Int,
    ) {
        inserted.removeAll {
            it.domainName == domainName && it.entityId == entityId && it.opType == opType && it.failureCount <= maxAttempts
        }
    }

    override fun observePending(maxAttempts: Int): Flow<List<PendingOperationV2Entity>> = flowOf(inserted.toList())

    override fun observeFailed(maxAttempts: Int): Flow<List<PendingOperationV2Entity>> = flowOf(emptyList())

    override suspend fun resetFailureCount(clientOpId: String) {
        inserted.replaceAll { if (it.clientOpId == clientOpId) it.copy(failureCount = 0, lastError = null) else it }
    }

    override fun observeQueueDepth(maxAttempts: Int): Flow<Int> = flowOf(inserted.count { it.failureCount <= maxAttempts })

    override fun observeDeadLetterCount(maxAttempts: Int): Flow<Int> = flowOf(0)

    override suspend fun deleteAllExcept(keepUserId: String) {
        inserted.removeAll { it.ownerUserId != keepUserId }
    }

    override suspend fun deleteAll() {
        inserted.clear()
    }

    override suspend fun maxEnqueuedAtFor(
        domainName: String,
        entityId: String,
    ): Long? = inserted.filter { it.domainName == domainName && it.entityId == entityId }.maxOfOrNull { it.enqueuedAt }

    override suspend fun gcDeadLetters(
        cutoffMillis: Long,
        maxAttempts: Int,
    ) {
        inserted.removeAll {
            it.failureCount > maxAttempts && (it.lastAttemptAt ?: it.enqueuedAt) < cutoffMillis
        }
    }
}

/**
 * Unit tests for [UserPreferencesRepositoryImpl].
 *
 * Covers the DTO → domain mapping, transport-error folding (the data layer never throws), and the
 * offline-first behaviour: write-through to Room on fetch, reactive [observePreferences], and
 * idempotent re-apply (no flicker on echo).
 *
 * The setters route through a real [OfflineEditor] (backed by a real [PendingOperationQueue] over
 * a [FakePendingOperationV2Dao], a pass-through [TransactionRunner], and [FakeAuthSession]) rather
 * than a mocked [UserPreferencesService] — since the offline-edit-sync generalization, a setter
 * enqueues a durable pending op instead of firing the RPC inline, so "sends only its field on the
 * patch" is now proven by decoding the enqueued payload rather than verifying a direct RPC call.
 */
class UserPreferencesRepositoryImplTest :
    FunSpec({
        val passthroughTransactionRunner =
            object : TransactionRunner {
                override suspend fun <R> atomically(block: suspend () -> R): R = block()
            }

        fun fixture(
            service: UserPreferencesService,
            dao: UserPreferencesDao = FakeUserPreferencesDao(),
            pendingOperationDao: PendingOperationV2Dao = FakePendingOperationV2Dao(),
        ): UserPreferencesRepositoryImpl {
            val offlineEditor =
                OfflineEditor(
                    pendingQueue =
                        PendingOperationQueue(
                            dao = pendingOperationDao,
                            sender = PendingOperationSender { AppResult.Success(Unit) },
                        ),
                    transactionRunner = passthroughTransactionRunner,
                    authSession = FakeAuthSession(userId = "u1"),
                )
            return UserPreferencesRepositoryImpl(
                RpcChannel.forTest(service),
                dao,
                FakeAuthSession(userId = "u1"),
                offlineEditor,
            )
        }

        test("getPreferences maps the DTO to the domain type") {
            runTest {
                val service =
                    mock<UserPreferencesService> {
                        everySuspend { getMyPreferences() } returns
                            AppResult.Success(UserPreferencesDto(1.5f, 30, 10, 20, true))
                    }
                val result = fixture(service).getPreferences()
                result.shouldBeInstanceOf<AppResult.Success<*>>()
                // Assert every field — distinct skip values (30 vs 10) would expose a forward/backward transposition.
                (result as AppResult.Success).data.defaultPlaybackSpeed shouldBe 1.5f
                result.data.defaultSkipForwardSec shouldBe 30
                result.data.defaultSkipBackwardSec shouldBe 10
                result.data.defaultSleepTimerMin shouldBe 20
                result.data.shakeToResetSleepTimer shouldBe true
            }
        }

        test("getPreferences writes the server result through to Room (offline-first)") {
            runTest {
                val dao = FakeUserPreferencesDao()
                val service =
                    mock<UserPreferencesService> {
                        everySuspend { getMyPreferences() } returns
                            AppResult.Success(UserPreferencesDto(1.5f, 45, 15, 20, true))
                    }
                fixture(service, dao).getPreferences()
                dao.get("u1") shouldBe UserPreferencesEntity("u1", 1.5f, 45, 15, 20, true)
            }
        }

        test("observePreferences emits defaults, then the cached value on update") {
            runTest {
                val dao = FakeUserPreferencesDao()
                val service = mock<UserPreferencesService>()
                val repo = fixture(service, dao)
                repo.observePreferences().test {
                    // Defaults before anything is cached.
                    awaitItem().defaultPlaybackSpeed shouldBe 1.0f
                    repo.setDefaultPlaybackSpeed(2.0f)
                    awaitItem().defaultPlaybackSpeed shouldBe 2.0f
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("re-applying identical values is a no-op — no extra emission (idempotent echo)") {
            runTest {
                val dao = FakeUserPreferencesDao()
                // Server always returns the same merged value, mimicking the echo of the device's own change.
                val service =
                    mock<UserPreferencesService> {
                        everySuspend { getMyPreferences() } returns
                            AppResult.Success(UserPreferencesDto(1.5f, 30, 10, null, false))
                    }
                val repo = fixture(service, dao)
                repo.observePreferences().test {
                    awaitItem().defaultPlaybackSpeed shouldBe 1.0f // defaults
                    repo.getPreferences() // first fetch writes the row
                    awaitItem().defaultPlaybackSpeed shouldBe 1.5f
                    repo.getPreferences() // identical re-fetch (the firehose echo)
                    repo.getPreferences()
                    expectNoEvents() // identical rows do not re-emit → no flicker
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // Each setter must enqueue exactly one field on the patch, addressed to the right DTO field,
        // and must NOT fire the RPC inline (the offline-edit-sync generalization routes every write
        // through the outbox; the mocked service below is never invoked).
        listOf<Triple<String, suspend UserPreferencesRepositoryImpl.() -> AppResult<Unit>, UpdateUserPreferencesRequest>>(
            Triple("setDefaultPlaybackSpeed", { setDefaultPlaybackSpeed(2.0f) }, UpdateUserPreferencesRequest(defaultPlaybackSpeed = 2.0f)),
            Triple("setDefaultSkipForwardSec", { setDefaultSkipForwardSec(45) }, UpdateUserPreferencesRequest(defaultSkipForwardSec = 45)),
            Triple("setDefaultSkipBackwardSec", { setDefaultSkipBackwardSec(15) }, UpdateUserPreferencesRequest(defaultSkipBackwardSec = 15)),
            Triple("setDefaultSleepTimerMin", { setDefaultSleepTimerMin(20) }, UpdateUserPreferencesRequest(defaultSleepTimerMin = 20)),
            Triple("setShakeToResetSleepTimer", { setShakeToResetSleepTimer(true) }, UpdateUserPreferencesRequest(shakeToResetSleepTimer = true)),
        ).forEach { (name, call, expectedPatch) ->
            test("$name enqueues only its field on the patch and returns Unit") {
                runTest {
                    // A bare mock: if the repo tried to push the RPC inline, the call would throw.
                    val service = mock<UserPreferencesService>()
                    val pendingOperationDao = FakePendingOperationV2Dao()
                    val repo = fixture(service, pendingOperationDao = pendingOperationDao)

                    repo.call() shouldBe AppResult.Success(Unit)

                    val op = pendingOperationDao.inserted.singleOrNull()
                    op.shouldNotBeNull()
                    op.domainName shouldBe "preferences"
                    contractJson.decodeFromString(UpdateUserPreferencesRequest.serializer(), op.payload) shouldBe expectedPatch
                }
            }
        }

        test("a thrown RPC error becomes AppResult.Failure") {
            runTest {
                val service =
                    mock<UserPreferencesService> {
                        everySuspend { getMyPreferences() } throws RuntimeException("ws down")
                    }
                fixture(service).getPreferences().shouldBeInstanceOf<AppResult.Failure>()
            }
        }
    })
