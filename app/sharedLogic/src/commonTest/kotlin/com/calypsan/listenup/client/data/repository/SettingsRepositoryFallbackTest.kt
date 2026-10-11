package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.connection.FallbackTrust
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.core.ServerUrl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val LOCAL = "http://192.168.1.20:8080"
private const val REMOTE = "https://wan.example.com"

private class MapSecureStorage : SecureStorage {
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

/** Records every question put to the gate and answers with [verdict]. */
private class RecordingGate(
    var verdict: Boolean,
) {
    val asked = mutableListOf<Pair<String, String?>>()

    fun allows(
        candidate: String,
        current: String?,
    ): Boolean {
        asked += candidate to current
        return verdict
    }
}

class SettingsRepositoryFallbackTest :
    FunSpec({
        suspend fun repository(
            gate: RecordingGate,
            remote: String? = REMOTE,
        ): SettingsRepositoryImpl {
            val storage = MapSecureStorage()
            storage.save("server_url", LOCAL)
            if (remote != null) storage.save("server_remote_url", remote)
            return SettingsRepositoryImpl(
                secureStorage = storage,
                authSession = lazy<AuthSession> { error("auth is not used by the fallback switch") },
                allowsFallback = gate::allows,
            )
        }

        test("a refused fallback is not returned, not persisted and not published") {
            val settings = repository(RecordingGate(verdict = false))

            settings.switchToFallbackUrl() shouldBe null

            settings.getActiveUrl() shouldBe ServerUrl(LOCAL)
            settings.activeUrl.value shouldBe null
        }

        test("an allowed fallback is returned, persisted and published") {
            val settings = repository(RecordingGate(verdict = true))

            settings.switchToFallbackUrl() shouldBe ServerUrl(REMOTE)

            settings.getActiveUrl() shouldBe ServerUrl(REMOTE)
            settings.activeUrl.value shouldBe ServerUrl(REMOTE)
        }

        test("the gate is asked about the candidate, with the address in use as the current one") {
            val gate = RecordingGate(verdict = true)
            val settings = repository(gate)

            settings.switchToFallbackUrl()

            gate.asked shouldBe listOf(REMOTE to LOCAL)
        }

        test("switching back from remote to local is gated the same way") {
            val gate = RecordingGate(verdict = true)
            val settings = repository(gate)
            settings.setActiveUrl(ServerUrl(REMOTE))

            settings.switchToFallbackUrl() shouldBe ServerUrl(LOCAL)

            gate.asked shouldBe listOf(LOCAL to REMOTE)
        }

        test("with no second address there is nothing to ask") {
            val gate = RecordingGate(verdict = true)
            val settings = repository(gate, remote = null)

            settings.switchToFallbackUrl() shouldBe null

            gate.asked shouldBe emptyList()
        }

        test("a stored remote address with no scheme is refused by the real trust check and never persisted") {
            val trust =
                FallbackTrust(
                    probe = { error("a scheme-less address must be refused before any probe") },
                    connectedServerId = { "A" },
                )
            val storage = MapSecureStorage()
            storage.save("server_url", LOCAL)
            storage.save("server_remote_url", "wan.example.com:8080")
            val settings =
                SettingsRepositoryImpl(
                    secureStorage = storage,
                    authSession = lazy<AuthSession> { error("auth is not used by the fallback switch") },
                    allowsFallback = trust::allows,
                )

            settings.switchToFallbackUrl() shouldBe null

            storage.read("active_url") shouldBe null
        }
    })
