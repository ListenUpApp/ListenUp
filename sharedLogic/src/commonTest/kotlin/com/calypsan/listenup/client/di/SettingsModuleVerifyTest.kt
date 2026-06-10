package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.core.SecureStorage
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [settingsModule]. Per the architecture rubric every leaf
 * Koin module is covered by a `module.verify()` test in commonTest.
 *
 * The whitelist enumerates dependencies the settings bindings pull in but
 * other modules own:
 *
 *  - [SecureStorage] — owned by `platformStorageModule` (token/URL persistence).
 *  - [AuthSession] — owned by `clientAuthModule`. Injected as `Lazy<AuthSession>`
 *    into [SettingsRepositoryImpl] to break the circular dependency with
 *    [com.calypsan.listenup.client.data.repository.AuthSessionStore].
 */
@OptIn(KoinExperimentalAPI::class)
class SettingsModuleVerifyTest :
    FunSpec({

        test("settingsModule wires up against its declared external dependencies") {
            settingsModule.verify(
                extraTypes =
                    listOf(
                        SecureStorage::class,
                        AuthSession::class,
                    ),
            )
        }
    })
