package com.calypsan.listenup.client.di

import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [appCoreModule]. Per the architecture rubric every leaf
 * Koin module is covered by a `module.verify()` test in commonTest.
 *
 * [appCoreModule] has no external dependencies — [ErrorBus], [DeepLinkManager],
 * [ShortcutActionManager], and the app-scoped [kotlinx.coroutines.CoroutineScope]
 * are all self-contained. The extra-types whitelist is therefore empty.
 */
@OptIn(KoinExperimentalAPI::class)
class AppCoreModuleVerifyTest :
    FunSpec({

        test("appCoreModule wires up against its declared external dependencies") {
            appCoreModule.verify(
                extraTypes = emptyList(),
            )
        }
    })
