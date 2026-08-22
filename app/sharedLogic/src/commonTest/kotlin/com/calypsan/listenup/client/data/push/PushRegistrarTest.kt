package com.calypsan.listenup.client.data.push

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.error.PushError
import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.PushRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/** Fake [PushTokenProvider] returning a fixed [token] (or `null` to simulate "no token yet"). */
private class FakePushTokenProvider(
    private val token: String?,
) : PushTokenProvider {
    override suspend fun currentToken(): String? = token
}

private fun serverInfo(pushEnabled: Boolean): ServerInfo =
    ServerInfo(
        name = "ListenUp",
        version = "1.0.0",
        apiVersion = "v1",
        setupRequired = false,
        registrationPolicy = RegistrationPolicy.CLOSED,
        pushEnabled = pushEnabled,
        instanceId = "instance-1",
    )

class PushRegistrarTest :
    FunSpec({

        test("registerRegistrationWatch earns the promise only on a successful registration") {
            runTest {
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { getServerInfoOrNull() } returns serverInfo(pushEnabled = true)
                    }
                val pushRepository =
                    mock<PushRepository> {
                        everySuspend { registerRegistrationWatchToken(any(), any(), any()) } returns AppResult.Success(Unit)
                    }
                val registrar = PushRegistrar(instanceRepository, pushRepository, FakePushTokenProvider("token-1"), PushPlatform.ANDROID)

                registrar.registerRegistrationWatch("user-1") shouldBe true
                verifySuspend { pushRepository.registerRegistrationWatchToken("user-1", "token-1", PushPlatform.ANDROID) }
            }
        }

        test("registerRegistrationWatch is false — no promise — without a provider, token, enabled push, or on failure") {
            runTest {
                val enabled =
                    mock<InstanceRepository> {
                        everySuspend { getServerInfoOrNull() } returns serverInfo(pushEnabled = true)
                    }
                val disabled =
                    mock<InstanceRepository> {
                        everySuspend { getServerInfoOrNull() } returns serverInfo(pushEnabled = false)
                    }
                val failing =
                    mock<PushRepository> {
                        everySuspend { registerRegistrationWatchToken(any(), any(), any()) } returns
                            AppResult.Failure(PushError.PushDisabled())
                    }
                val unusedRepo = mock<PushRepository>()

                PushRegistrar(enabled, unusedRepo, tokenProvider = null, platform = PushPlatform.ANDROID)
                    .registerRegistrationWatch("u") shouldBe false
                PushRegistrar(enabled, unusedRepo, FakePushTokenProvider(null), PushPlatform.ANDROID)
                    .registerRegistrationWatch("u") shouldBe false
                PushRegistrar(disabled, unusedRepo, FakePushTokenProvider("t"), PushPlatform.ANDROID)
                    .registerRegistrationWatch("u") shouldBe false
                PushRegistrar(enabled, failing, FakePushTokenProvider("t"), PushPlatform.ANDROID)
                    .registerRegistrationWatch("u") shouldBe false
            }
        }

        test("registers the current token when pushEnabled") {
            runTest {
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { getServerInfoOrNull() } returns serverInfo(pushEnabled = true)
                    }
                val pushRepository =
                    mock<PushRepository> {
                        everySuspend { registerToken(any(), any()) } returns AppResult.Success(Unit)
                    }
                val registrar = PushRegistrar(instanceRepository, pushRepository, FakePushTokenProvider("token-1"), PushPlatform.ANDROID)

                registrar.syncRegistration()

                verifySuspend { pushRepository.registerToken("token-1", PushPlatform.ANDROID) }
            }
        }

        test("no-ops when ServerInfo.pushEnabled is false") {
            runTest {
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { getServerInfoOrNull() } returns serverInfo(pushEnabled = false)
                    }
                val pushRepository = mock<PushRepository>()
                val registrar = PushRegistrar(instanceRepository, pushRepository, FakePushTokenProvider("token-1"), PushPlatform.ANDROID)

                registrar.syncRegistration()

                verifySuspend(exactly(0)) { pushRepository.registerToken(any(), any()) }
            }
        }

        test("no-ops when no token provider is bound (desktop/de-googled)") {
            runTest {
                val instanceRepository = mock<InstanceRepository>()
                val pushRepository = mock<PushRepository>()
                val registrar = PushRegistrar(instanceRepository, pushRepository, tokenProvider = null, platform = PushPlatform.ANDROID)

                registrar.syncRegistration()

                verifySuspend(exactly(0)) { instanceRepository.getServerInfoOrNull() }
                verifySuspend(exactly(0)) { pushRepository.registerToken(any(), any()) }
            }
        }

        test("no-ops when provider returns null (no Play services)") {
            runTest {
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { getServerInfoOrNull() } returns serverInfo(pushEnabled = true)
                    }
                val pushRepository = mock<PushRepository>()
                val registrar = PushRegistrar(instanceRepository, pushRepository, FakePushTokenProvider(null), PushPlatform.ANDROID)

                registrar.syncRegistration()

                verifySuspend(exactly(0)) { pushRepository.registerToken(any(), any()) }
            }
        }

        test("onTokenRotated re-registers with the new token") {
            runTest {
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { getServerInfoOrNull() } returns serverInfo(pushEnabled = true)
                    }
                val pushRepository =
                    mock<PushRepository> {
                        everySuspend { registerToken(any(), any()) } returns AppResult.Success(Unit)
                    }
                val registrar = PushRegistrar(instanceRepository, pushRepository, FakePushTokenProvider("stale"), PushPlatform.ANDROID)

                registrar.onTokenRotated("rotated-token")

                verifySuspend { pushRepository.registerToken("rotated-token", PushPlatform.ANDROID) }
            }
        }

        // ⛔ The web/desktop regression. `PushPlatform` has values only for Android and iOS, and it
        // used to be a hard `get()` inside `PushRepositoryImpl`'s construction — so on a build with
        // no push at all, merely RESOLVING the push graph threw. That happened inside
        // `refetchServerInfo`, whose caller swallows exceptions, so it showed up as nothing but a
        // recurring "Refresh refetch failed" line while silently truncating that refetch.
        test("a build with no push platform registers nothing instead of failing to construct") {
            runTest {
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { getServerInfoOrNull() } returns serverInfo(pushEnabled = true)
                    }
                val pushRepository = mock<PushRepository>()
                val registrar =
                    PushRegistrar(
                        instanceRepository,
                        pushRepository,
                        FakePushTokenProvider("token-1"),
                        platform = null,
                    )

                registrar.syncRegistration()
                registrar.onTokenRotated("rotated")
                val watched = registrar.registerRegistrationWatch("user-1")

                watched shouldBe false
                verifySuspend(exactly(0)) { pushRepository.registerToken(any(), any()) }
                verifySuspend(exactly(0)) { pushRepository.registerRegistrationWatchToken(any(), any(), any()) }
            }
        }

        test("registration failure is swallowed after logging (never throws)") {
            runTest {
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { getServerInfoOrNull() } returns serverInfo(pushEnabled = true)
                    }
                val pushRepository =
                    mock<PushRepository> {
                        everySuspend { registerToken(any(), any()) } returns AppResult.Failure(PushError.PushDisabled())
                    }
                val registrar = PushRegistrar(instanceRepository, pushRepository, FakePushTokenProvider("token-1"), PushPlatform.ANDROID)

                // Must not throw.
                registrar.syncRegistration()
            }
        }
    })
