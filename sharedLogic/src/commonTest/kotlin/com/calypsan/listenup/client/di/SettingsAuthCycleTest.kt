package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.client.data.repository.AuthSessionStore
import com.calypsan.listenup.client.data.repository.SettingsRepositoryImpl
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.VerifiedServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Regression test for the SettingsRepositoryImpl ↔ AuthSession Koin constructor cycle.
 *
 * Without the fix the graph resolves:
 *   SettingsRepositoryImpl(authSession=get()) → AuthSessionStore(serverConfig=get())
 *   → single<ServerConfig> { get<SettingsRepositoryImpl>() } → SettingsRepositoryImpl
 *   → ... → StackOverflowError
 *
 * [module.verify()] cannot catch this because it never instantiates — it only
 * checks that declarations exist. This test resolves the real instances, which
 * WILL StackOverflow before the fix and succeed after it.
 */
class SettingsAuthCycleTest :
    FunSpec({

        afterEach { stopKoin() }

        test("resolving ServerConfig does not StackOverflow — SettingsRepositoryImpl lazy AuthSession cycle") {
            // Minimal in-memory fakes for leaf externals.
            val fakeStorage = InMemorySecureStorage()
            val fakeInstanceRepository = NoOpInstanceRepository()

            val testModule =
                module {
                    // Leaf externals
                    single<SecureStorage> { fakeStorage }
                    single<InstanceRepository> { fakeInstanceRepository }

                    // Real bindings that form the cycle — copied verbatim from the fixed
                    // dataModule / clientAuthModule bindings. The lazy wrapper defers
                    // AuthSession resolution until first suspend-method use, breaking the
                    // SettingsRepositoryImpl → AuthSession → ServerConfig → SettingsRepositoryImpl
                    // construction-time cycle.
                    single {
                        val scope = this
                        SettingsRepositoryImpl(
                            secureStorage = get(),
                            authSession = lazy { scope.get<AuthSession>() },
                        )
                    }
                    single<ServerConfig> { get<SettingsRepositoryImpl>() }

                    singleOf(::AuthSessionStore) bind AuthSession::class
                }

            val koin = koinApplication { modules(testModule) }.koin

            // This is the line that stack-overflows without the fix.
            val serverConfig = koin.get<ServerConfig>()
            serverConfig shouldNotBe null
        }
    })

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

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

private class NoOpInstanceRepository : InstanceRepository {
    override suspend fun findReachableUrl(urls: List<String>): String? = null

    override suspend fun getServerInfo(forceRefresh: Boolean): AppResult<com.calypsan.listenup.api.dto.ServerInfo> =
        AppResult.Failure(
            com.calypsan.listenup.api.error.TransportError.NetworkUnavailable(
                debugInfo = "NoOpInstanceRepository",
            ),
        )

    override suspend fun verifyServer(baseUrl: String): AppResult<VerifiedServer> =
        AppResult.Failure(
            com.calypsan.listenup.api.error.TransportError.NetworkUnavailable(
                debugInfo = "NoOpInstanceRepository",
            ),
        )
}
