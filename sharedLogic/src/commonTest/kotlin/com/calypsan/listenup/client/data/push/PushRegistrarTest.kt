package com.calypsan.listenup.client.data.push

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.error.PushError
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

        test("registers the current token when pushEnabled") {
            runTest {
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { getServerInfoOrNull() } returns serverInfo(pushEnabled = true)
                    }
                val pushRepository =
                    mock<PushRepository> {
                        everySuspend { registerToken(any()) } returns AppResult.Success(Unit)
                    }
                val registrar = PushRegistrar(instanceRepository, pushRepository, FakePushTokenProvider("token-1"))

                registrar.syncRegistration()

                verifySuspend { pushRepository.registerToken("token-1") }
            }
        }

        test("no-ops when ServerInfo.pushEnabled is false") {
            runTest {
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { getServerInfoOrNull() } returns serverInfo(pushEnabled = false)
                    }
                val pushRepository = mock<PushRepository>()
                val registrar = PushRegistrar(instanceRepository, pushRepository, FakePushTokenProvider("token-1"))

                registrar.syncRegistration()

                verifySuspend(exactly(0)) { pushRepository.registerToken(any()) }
            }
        }

        test("no-ops when no token provider is bound (desktop/de-googled)") {
            runTest {
                val instanceRepository = mock<InstanceRepository>()
                val pushRepository = mock<PushRepository>()
                val registrar = PushRegistrar(instanceRepository, pushRepository, tokenProvider = null)

                registrar.syncRegistration()

                verifySuspend(exactly(0)) { instanceRepository.getServerInfoOrNull() }
                verifySuspend(exactly(0)) { pushRepository.registerToken(any()) }
            }
        }

        test("no-ops when provider returns null (no Play services)") {
            runTest {
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { getServerInfoOrNull() } returns serverInfo(pushEnabled = true)
                    }
                val pushRepository = mock<PushRepository>()
                val registrar = PushRegistrar(instanceRepository, pushRepository, FakePushTokenProvider(null))

                registrar.syncRegistration()

                verifySuspend(exactly(0)) { pushRepository.registerToken(any()) }
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
                        everySuspend { registerToken(any()) } returns AppResult.Success(Unit)
                    }
                val registrar = PushRegistrar(instanceRepository, pushRepository, FakePushTokenProvider("stale"))

                registrar.onTokenRotated("rotated-token")

                verifySuspend { pushRepository.registerToken("rotated-token") }
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
                        everySuspend { registerToken(any()) } returns AppResult.Failure(PushError.PushDisabled())
                    }
                val registrar = PushRegistrar(instanceRepository, pushRepository, FakePushTokenProvider("token-1"))

                // Must not throw.
                registrar.syncRegistration()
            }
        }
    })
