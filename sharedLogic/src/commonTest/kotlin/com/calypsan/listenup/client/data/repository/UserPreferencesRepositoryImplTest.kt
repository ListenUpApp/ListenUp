package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.preferences.UserPreferencesDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.UserPreferencesDao
import com.calypsan.listenup.client.data.local.db.UserPreferencesEntity
import com.calypsan.listenup.client.data.remote.UserPreferencesRpcFactory
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Unit tests for [UserPreferencesRepositoryImpl].
 *
 * Covers the DTO → domain mapping, single-field PATCH semantics, transport-error folding (the data
 * layer never throws), and the offline-first behaviour: write-through to Room on fetch, reactive
 * [observePreferences], and idempotent re-apply (no flicker on echo).
 */
class UserPreferencesRepositoryImplTest :
    FunSpec({
        fun fixture(
            service: UserPreferencesService,
            dao: UserPreferencesDao = FakeUserPreferencesDao(),
        ): UserPreferencesRepositoryImpl {
            val factory = mock<UserPreferencesRpcFactory> { everySuspend { get() } returns service }
            return UserPreferencesRepositoryImpl(factory, dao, FakeAuthSession(userId = "u1"))
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
                val service =
                    mock<UserPreferencesService> {
                        everySuspend { updateMyPreferences(any()) } returns
                            AppResult.Success(UserPreferencesDto(2.0f, 30, 10, null, false))
                    }
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

        // Each setter must send exactly one field on the patch, addressed to the right DTO field.
        listOf<Triple<String, suspend UserPreferencesRepositoryImpl.() -> AppResult<Unit>, UpdateUserPreferencesRequest>>(
            Triple("setDefaultPlaybackSpeed", { setDefaultPlaybackSpeed(2.0f) }, UpdateUserPreferencesRequest(defaultPlaybackSpeed = 2.0f)),
            Triple("setDefaultSkipForwardSec", { setDefaultSkipForwardSec(45) }, UpdateUserPreferencesRequest(defaultSkipForwardSec = 45)),
            Triple("setDefaultSkipBackwardSec", { setDefaultSkipBackwardSec(15) }, UpdateUserPreferencesRequest(defaultSkipBackwardSec = 15)),
            Triple("setDefaultSleepTimerMin", { setDefaultSleepTimerMin(20) }, UpdateUserPreferencesRequest(defaultSleepTimerMin = 20)),
            Triple("setShakeToResetSleepTimer", { setShakeToResetSleepTimer(true) }, UpdateUserPreferencesRequest(shakeToResetSleepTimer = true)),
        ).forEach { (name, call, expectedPatch) ->
            test("$name sends only its field on the patch and returns Unit") {
                runTest {
                    val service =
                        mock<UserPreferencesService> {
                            everySuspend { updateMyPreferences(any()) } returns
                                AppResult.Success(UserPreferencesDto(1.0f, 30, 10, null, false))
                        }
                    fixture(service).call() shouldBe AppResult.Success(Unit)
                    verifySuspend { service.updateMyPreferences(expectedPatch) }
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
