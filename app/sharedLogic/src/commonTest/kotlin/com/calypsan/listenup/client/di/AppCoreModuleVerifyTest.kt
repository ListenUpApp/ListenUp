package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.AuthSession
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [appCoreModule]. Per the architecture rubric every leaf
 * Koin module is covered by a `module.verify()` test in commonTest.
 *
 * [ErrorBus], [DeepLinkManager], [ShortcutActionManager], and the app-scoped
 * [kotlinx.coroutines.CoroutineScope] are self-contained. [AuthSession] is the one
 * external dependency — the [com.calypsan.listenup.client.data.auth.AuthFailureObserver]
 * consumes it — so it is whitelisted as externally provided.
 */
@OptIn(KoinExperimentalAPI::class)
class AppCoreModuleVerifyTest :
    FunSpec({

        test("appCoreModule wires up against its declared external dependencies") {
            appCoreModule.verify(
                extraTypes = listOf(AuthSession::class),
            )
        }
    })
