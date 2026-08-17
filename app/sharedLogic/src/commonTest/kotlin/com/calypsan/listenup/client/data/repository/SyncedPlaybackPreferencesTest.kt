package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.test.fake.FakeUserPreferencesRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * [SyncedPlaybackPreferences] is the player's view of the server-synced defaults. These tests pin
 * the two properties the split store used to break: every read comes from the synced store (so the
 * player and the Settings screen can't disagree), and no read touches the network (so an
 * unreachable server never silently demotes a 2x default to 1x).
 */
class SyncedPlaybackPreferencesTest :
    FunSpec({

        val synced =
            FakeUserPreferencesRepository.DEFAULTS.copy(
                defaultPlaybackSpeed = 2.0f,
                defaultVolumeBoostDb = 6.0f,
                defaultSkipForwardSec = 45,
                defaultSkipBackwardSec = 15,
            )

        test("every default is read from the synced store") {
            runTest {
                val preferences = SyncedPlaybackPreferences(FakeUserPreferencesRepository(synced))

                preferences.getDefaultPlaybackSpeed() shouldBe 2.0f
                preferences.getDefaultVolumeBoostDb() shouldBe 6.0f
                preferences.getDefaultSkipForwardSec() shouldBe 45
                preferences.getDefaultSkipBackwardSec() shouldBe 15
            }
        }

        test("an account with nothing cached falls back to the stock defaults") {
            runTest {
                val preferences = SyncedPlaybackPreferences(FakeUserPreferencesRepository())

                preferences.getDefaultPlaybackSpeed() shouldBe 1.0f
                preferences.getDefaultVolumeBoostDb() shouldBe 0.0f
                preferences.getDefaultSkipForwardSec() shouldBe 30
                preferences.getDefaultSkipBackwardSec() shouldBe 10
            }
        }

        test("reads never reach for the server — a dead session cannot demote the defaults") {
            runTest {
                val repository = FakeUserPreferencesRepository(synced)
                repository.failGetPreferences = InternalError(debugInfo = "session lapsed")
                val preferences = SyncedPlaybackPreferences(repository)

                preferences.getDefaultPlaybackSpeed() shouldBe 2.0f
                preferences.getDefaultSkipForwardSec() shouldBe 45
                repository.getPreferencesCalls shouldBe 0
            }
        }

        test("a change to the synced default re-emits on the observed flow") {
            runTest {
                val repository = FakeUserPreferencesRepository()
                val preferences = SyncedPlaybackPreferences(repository)

                preferences.observeDefaultPlaybackSpeed().test {
                    awaitItem() shouldBe 1.0f
                    repository.setDefaultPlaybackSpeed(1.75f)
                    awaitItem() shouldBe 1.75f
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a change to an unrelated preference does not re-emit on the speed flow") {
            runTest {
                val repository = FakeUserPreferencesRepository()
                val preferences = SyncedPlaybackPreferences(repository)

                preferences.observeDefaultPlaybackSpeed().test {
                    awaitItem() shouldBe 1.0f
                    repository.setDefaultSkipForwardSec(45)
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("the skip and boost flows follow their own synced fields") {
            runTest {
                val repository = FakeUserPreferencesRepository()
                val preferences = SyncedPlaybackPreferences(repository)

                preferences.observeDefaultSkipForwardSec().test {
                    awaitItem() shouldBe 30
                    repository.setDefaultSkipForwardSec(45)
                    awaitItem() shouldBe 45
                    cancelAndIgnoreRemainingEvents()
                }
                preferences.observeDefaultSkipBackwardSec().test {
                    awaitItem() shouldBe 10
                    repository.setDefaultSkipBackwardSec(15)
                    awaitItem() shouldBe 15
                    cancelAndIgnoreRemainingEvents()
                }
                preferences.observeDefaultVolumeBoostDb().test {
                    awaitItem() shouldBe 0.0f
                    repository.setDefaultVolumeBoostDb(6.0f)
                    awaitItem() shouldBe 6.0f
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
