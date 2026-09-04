package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.version.ClientIdentity
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [networkModule]. Per the architecture rubric every leaf
 * Koin module is covered by a `module.verify()` test in commonTest.
 *
 * The whitelist enumerates dependencies the network bindings pull in but
 * other modules own:
 *
 *  - [ServerConfig] — owned by `settingsModule` (segregated interface → `SettingsRepositoryImpl`).
 *    Used by [com.calypsan.listenup.client.data.remote.ApiClientFactory] to read the current URL.
 *  - [AuthSession] — owned by `clientAuthModule` (token storage + auth-state flow).
 *    Used by [com.calypsan.listenup.client.data.remote.ApiClientFactory] to attach bearer tokens.
 *  - [com.calypsan.listenup.client.domain.repository.AuthRepository] — owned by `clientAuthModule`.
 *    Resolved lazily by `ApiClientFactory`'s `refreshAccessToken` lambda on 401 responses.
 *  - [Function1] — Koin's verify treats constructor lambda params as unresolved types;
 *    `ApiClientFactory`'s `refreshAccessToken: suspend () -> AppResult<AuthSession>` is
 *    compiled to a `Function1<Continuation<AppResult<AuthSession>>, Any?>` and must be
 *    listed here so the verifier skips it.
 *  - [ClientIdentity] — owned by `appCoreModule`. Announced to the server via
 *    `X-Client-Version`/`X-Client-Api` on every request the factory builds.
 *  - [LocalPreferences] — owned by `settingsModule`. `ApiClientFactory`'s `onPeerVersion`
 *    callback persists the peer server's version captured off response headers there.
 */
@OptIn(KoinExperimentalAPI::class)
class NetworkModuleVerifyTest :
    FunSpec({

        test("networkModule wires up against its declared external dependencies") {
            networkModule.verify(
                extraTypes =
                    listOf(
                        ServerConfig::class,
                        AuthSession::class,
                        com.calypsan.listenup.client.domain.repository.AuthRepository::class,
                        Function1::class,
                        ClientIdentity::class,
                        LocalPreferences::class,
                    ),
            )
        }
    })
