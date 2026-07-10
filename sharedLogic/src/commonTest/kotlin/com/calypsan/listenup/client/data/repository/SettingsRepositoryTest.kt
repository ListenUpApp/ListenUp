package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.PreferenceChangeEvent
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import com.calypsan.listenup.client.domain.model.AuthState as DomainAuthState

/**
 * Tests for the *non-auth* surface of [SettingsRepositoryImpl] — server URL
 * plumbing, library identity, library + playback preferences. The auth
 * slice has its own test in [AuthSessionStoreTest]; here we mock the
 * `AuthSession` collaborator at the seam.
 */
class SettingsRepositoryTest :
    FunSpec({
        fun createMockStorage(): SecureStorage = mock<SecureStorage>()

        fun createMockAuthSession(): AuthSession = mock<AuthSession>()

        fun createRepository(
            storage: SecureStorage = createMockStorage(),
            authSession: AuthSession = createMockAuthSession(),
        ): SettingsRepositoryImpl = SettingsRepositoryImpl(storage, lazyOf(authSession))

        test("setServerUrl persists the URL and triggers offline derive when already authenticated") {
            runTest {
                val storage = createMockStorage()
                val authSession = createMockAuthSession()
                everySuspend { storage.save("server_url", "https://api.example.com") } returns Unit
                everySuspend { storage.read("active_url") } returns null
                everySuspend { storage.read("server_url") } returns "https://api.example.com"
                everySuspend { authSession.isAuthenticated() } returns true
                everySuspend { authSession.initializeAuthState() } returns Unit
                val repository = createRepository(storage = storage, authSession = authSession)

                repository.setServerUrl(ServerUrl("https://api.example.com"))

                verifySuspend { storage.save("server_url", "https://api.example.com") }
                verifySuspend { authSession.initializeAuthState() }
            }
        }

        test("setServerUrl persists the URL and asks server status when not yet authenticated") {
            runTest {
                val storage = createMockStorage()
                val authSession = createMockAuthSession()
                val authStateFlow: StateFlow<DomainAuthState> = MutableStateFlow(DomainAuthState.Initializing)
                everySuspend { storage.save("server_url", "https://api.example.com") } returns Unit
                everySuspend { storage.read("active_url") } returns null
                everySuspend { storage.read("server_url") } returns "https://api.example.com"
                everySuspend { authSession.isAuthenticated() } returns false
                everySuspend { authSession.checkServerStatus() } returns DomainAuthState.NeedsLogin()
                // checkServerStatus is suspend & returns AuthState; just stub it.
                val repository = createRepository(storage = storage, authSession = authSession)

                repository.setServerUrl(ServerUrl("https://api.example.com"))

                verifySuspend { storage.save("server_url", "https://api.example.com") }
                verifySuspend { authSession.checkServerStatus() }
                // authStateFlow is unused but kept to document the seam shape.
                (authStateFlow.value as? DomainAuthState.NeedsLogin) shouldBe null
            }
        }

        test("getServerUrl returns stored URL") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("server_url") } returns "https://api.example.com"
                val repository = createRepository(storage = storage)

                repository.getServerUrl() shouldBe ServerUrl("https://api.example.com")
            }
        }

        test("getServerUrl returns null when not configured") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("server_url") } returns null
                val repository = createRepository(storage = storage)

                repository.getServerUrl() shouldBe null
            }
        }

        test("clearAll wipes secure storage and re-derives auth state") {
            runTest {
                val storage = createMockStorage()
                val authSession = createMockAuthSession()
                everySuspend { storage.clear() } returns Unit
                everySuspend { storage.read(any()) } returns null
                everySuspend { authSession.initializeAuthState() } returns Unit
                val repository = createRepository(storage = storage, authSession = authSession)

                repository.clearAll()

                verifySuspend { storage.clear() }
                verifySuspend { authSession.initializeAuthState() }
            }
        }

        test("disconnectFromServer drops auth and URL plumbing then re-derives state") {
            runTest {
                val storage = createMockStorage()
                val authSession = createMockAuthSession()
                everySuspend { authSession.clearAuthTokens() } returns Unit
                everySuspend { authSession.clearPendingRegistration() } returns Unit
                everySuspend { authSession.initializeAuthState() } returns Unit
                everySuspend { storage.delete(any()) } returns Unit
                everySuspend { storage.read(any()) } returns null
                val repository = createRepository(storage = storage, authSession = authSession)

                repository.disconnectFromServer()

                verifySuspend { authSession.clearAuthTokens() }
                verifySuspend { authSession.clearPendingRegistration() }
                verifySuspend { storage.delete("server_url") }
                verifySuspend { storage.delete("server_remote_url") }
                verifySuspend { storage.delete("active_url") }
                verifySuspend { storage.delete("connected_library_id") }
                verifySuspend { authSession.initializeAuthState() }
            }
        }

        test("hasServerConfigured returns true when URL configured") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("server_url") } returns "https://api.example.com"
                val repository = createRepository(storage = storage)

                repository.hasServerConfigured() shouldBe true
            }
        }

        test("hasServerConfigured returns false when URL not configured") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("server_url") } returns null
                val repository = createRepository(storage = storage)

                repository.hasServerConfigured() shouldBe false
            }
        }

        // ========== Active URL change-signal ==========

        test("setActiveUrl persists the active URL and publishes it") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.save("active_url", "http://192.168.1.10:8080") } returns Unit
                everySuspend { storage.read("active_url") } returns "http://192.168.1.10:8080"
                val repository = createRepository(storage = storage)

                repository.activeUrl.test {
                    awaitItem() // current value (null initial)
                    repository.setActiveUrl(ServerUrl("http://192.168.1.10:8080"))
                    awaitItem()?.value shouldBe "http://192.168.1.10:8080"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ========== Connected mDNS server id + local URL follow ==========

        test("setConnectedServerId persists and getConnectedServerId reads it back") {
            runTest {
                val storage = createMockStorage()
                val repository = createRepository(storage = storage)
                everySuspend { storage.save("connected_server_id", "abc-123") } returns Unit
                everySuspend { storage.read("connected_server_id") } returns "abc-123"

                repository.setConnectedServerId("abc-123")

                repository.getConnectedServerId() shouldBe "abc-123"
            }
        }

        test("setConnectedServerId null clears it") {
            runTest {
                val storage = createMockStorage()
                val repository = createRepository(storage = storage)
                everySuspend { storage.delete("connected_server_id") } returns Unit

                repository.setConnectedServerId(null)

                verifySuspend { storage.delete("connected_server_id") }
            }
        }

        test("updateLocalUrl saves the local URL and publishes activeUrl") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.save("server_url", "http://192.168.1.20:8080") } returns Unit
                everySuspend { storage.read("active_url") } returns null
                everySuspend { storage.read("server_url") } returns "http://192.168.1.20:8080"
                everySuspend { storage.read("server_remote_url") } returns null
                val repository = createRepository(storage = storage)

                repository.activeUrl.test {
                    awaitItem() // current value (null initial)
                    repository.updateLocalUrl(ServerUrl("http://192.168.1.20:8080"))
                    awaitItem()?.value shouldBe "http://192.168.1.20:8080"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ========== Default playback speed ==========

        test("setDefaultPlaybackSpeed saves speed and emits preference change event") {
            @OptIn(ExperimentalCoroutinesApi::class)
            runTest(UnconfinedTestDispatcher()) {
                val storage = createMockStorage()
                everySuspend { storage.save("default_playback_speed", "1.5") } returns Unit
                val repository = createRepository(storage = storage)

                // Start collecting before emitting (async starts immediately under UnconfinedTestDispatcher)
                val eventDeferred = async { repository.preferenceChanges.first() }

                repository.setDefaultPlaybackSpeed(1.5f)

                val receivedEvent = eventDeferred.await()
                verifySuspend { storage.save("default_playback_speed", "1.5") }
                val speedChangedEvent = receivedEvent.shouldBeInstanceOf<PreferenceChangeEvent.PlaybackSpeedChanged>()
                speedChangedEvent.speed shouldBe 1.5f
            }
        }

        test("getDefaultPlaybackSpeed returns default when not set") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("default_playback_speed") } returns null
                val repository = createRepository(storage = storage)

                repository.getDefaultPlaybackSpeed() shouldBe 1.0f
            }
        }

        test("getDefaultPlaybackSpeed returns stored speed") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("default_playback_speed") } returns "1.25"
                val repository = createRepository(storage = storage)

                repository.getDefaultPlaybackSpeed() shouldBe 1.25f
            }
        }

        test("observeDefaultPlaybackSpeed emits current value on first collect") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("default_playback_speed") } returns "1.5"
                val repository = createRepository(storage = storage)

                repository.observeDefaultPlaybackSpeed().test {
                    awaitItem() shouldBe 1.5f
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeDefaultPlaybackSpeed re-emits when setDefaultPlaybackSpeed is called") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("default_playback_speed") } returns "1.0"
                everySuspend { storage.save("default_playback_speed", "1.75") } returns Unit
                val repository = createRepository(storage = storage)

                repository.observeDefaultPlaybackSpeed().test {
                    awaitItem() shouldBe 1.0f
                    repository.setDefaultPlaybackSpeed(1.75f)
                    awaitItem() shouldBe 1.75f
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ========== Default skip intervals ==========

        test("getDefaultSkipForwardSec / getDefaultSkipBackwardSec return defaults when unset") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("default_skip_forward_sec") } returns null
                everySuspend { storage.read("default_skip_backward_sec") } returns null
                val repository = createRepository(storage = storage)

                repository.getDefaultSkipForwardSec() shouldBe 30
                repository.getDefaultSkipBackwardSec() shouldBe 10
            }
        }

        test("observeDefaultSkipForwardSec emits current value then re-emits on write") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("default_skip_forward_sec") } returns "30"
                everySuspend { storage.save("default_skip_forward_sec", "45") } returns Unit
                val repository = createRepository(storage = storage)

                repository.observeDefaultSkipForwardSec().test {
                    awaitItem() shouldBe 30
                    repository.setDefaultSkipForwardSec(45)
                    awaitItem() shouldBe 45
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeDefaultSkipBackwardSec emits current value then re-emits on write") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("default_skip_backward_sec") } returns "10"
                everySuspend { storage.save("default_skip_backward_sec", "5") } returns Unit
                val repository = createRepository(storage = storage)

                repository.observeDefaultSkipBackwardSec().test {
                    awaitItem() shouldBe 10
                    repository.setDefaultSkipBackwardSec(5)
                    awaitItem() shouldBe 5
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ========== Connection-health persistence (peer version + outdated dismissal) ==========
        //
        // The join/split round-trip, the malformed-value guard, and the delete-on-null branch are
        // real persistence code that mocks can't exercise — these use a stateful in-memory
        // SecureStorage so a fresh instance genuinely re-hydrates what a prior one wrote.

        test("setPeerServerVersion round-trips through storage on re-hydrate") {
            runTest {
                val storage = InMemorySecureStorage()
                createRepository(storage = storage).setPeerServerVersion("0.7.0", "v1")

                val rehydrated = createRepository(storage = storage)
                rehydrated.initializeLocalPreferences()

                rehydrated.peerServerVersion.value shouldBe "0.7.0"
                rehydrated.peerServerApi.value shouldBe "v1"
            }
        }

        test("setOutdatedDismissedFor round-trips the pair through storage on re-hydrate") {
            runTest {
                val storage = InMemorySecureStorage()
                createRepository(storage = storage).setOutdatedDismissedFor("0.6.0" to "0.7.0")

                val rehydrated = createRepository(storage = storage)
                rehydrated.initializeLocalPreferences()

                rehydrated.outdatedDismissedFor.value shouldBe ("0.6.0" to "0.7.0")
            }
        }

        test("setOutdatedDismissedFor(null) clears the persisted pair (delete branch)") {
            runTest {
                val storage = InMemorySecureStorage()
                val repository = createRepository(storage = storage)
                repository.setOutdatedDismissedFor("0.6.0" to "0.7.0")
                repository.setOutdatedDismissedFor(null)

                val rehydrated = createRepository(storage = storage)
                rehydrated.initializeLocalPreferences()

                rehydrated.outdatedDismissedFor.value shouldBe null
            }
        }

        test("a malformed persisted dismissal value hydrates to null (guard)") {
            runTest {
                val storage = InMemorySecureStorage()
                storage.save("outdated_dismissed", "garbage")

                val repository = createRepository(storage = storage)
                repository.initializeLocalPreferences()

                repository.outdatedDismissedFor.value shouldBe null
            }
        }
    })

/** Stateful in-memory [SecureStorage] so persistence round-trips are exercised, not mocked. */
private class InMemorySecureStorage : SecureStorage {
    private val store = mutableMapOf<String, String>()

    override suspend fun save(
        key: String,
        value: String,
    ) {
        store[key] = value
    }

    override suspend fun read(key: String): String? = store[key]

    override suspend fun delete(key: String) {
        store.remove(key)
    }

    override suspend fun clear() {
        store.clear()
    }
}
