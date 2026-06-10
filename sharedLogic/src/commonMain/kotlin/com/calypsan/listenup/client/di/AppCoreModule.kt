package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.repository.DeepLinkManager
import com.calypsan.listenup.client.data.repository.ShortcutActionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Koin qualifier for the application-lifetime [CoroutineScope]. */
private const val APP_SCOPE = "appScope"

/**
 * App-wide infrastructure bindings that don't belong to a single domain:
 * the global error bus, deep-link routing, shortcut dispatch, and the
 * application-lifetime coroutine scope.
 */
val appCoreModule: Module
    get() =
        module {
            // Error bus — single instance shared by every emitter (data layer, ViewModels)
            // and the single subscriber (GlobalErrorSnackbar in AppShell).
            single {
                com.calypsan.listenup.core.error
                    .ErrorBus()
            }

            // Deep link manager - singleton for handling invite deep links
            // Must be initialized before MainActivity handles intents
            single { DeepLinkManager() }

            // Shortcut action manager - singleton for handling app shortcut intents
            // Observed by navigation layer to execute shortcut actions
            single { ShortcutActionManager() }

            // Application-scoped CoroutineScope for long-lived background operations.
            // Used by sync and playback tasks that span the app's lifetime.
            // SupervisorJob ensures child failures don't cancel siblings.
            single<CoroutineScope>(
                qualifier =
                    named(APP_SCOPE),
            ) {
                CoroutineScope(
                    SupervisorJob() + Dispatchers.Default,
                )
            }
        }
