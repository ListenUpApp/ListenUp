package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.EXPECTED_API_VERSION
import com.calypsan.listenup.client.core.appCoroutineExceptionHandler
import com.calypsan.listenup.client.data.auth.AuthFailureObserver
import com.calypsan.listenup.client.data.repository.DeepLinkManager
import com.calypsan.listenup.client.data.repository.ShortcutActionManager
import com.calypsan.listenup.client.domain.version.ClientIdentity
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
internal val appCoreModule: Module =
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
        // SupervisorJob ensures child failures don't cancel siblings; the
        // appCoroutineExceptionHandler keeps an uncaught failure in a fire-and-forget
        // launch (e.g. the realtime RPC socket dropping) from terminating the process
        // on Kotlin/Native — it logs loudly instead. See [appCoroutineExceptionHandler].
        single<CoroutineScope>(
            qualifier =
                named(APP_SCOPE),
        ) {
            CoroutineScope(
                SupervisorJob() + Dispatchers.Default + appCoroutineExceptionHandler,
            )
        }

        // Global auth-failure watcher: a session-invalidating AuthError on the bus
        // soft-logs-out → login. createdAtStart so it subscribes before
        // any auth error can be emitted.
        single(createdAtStart = true) {
            AuthFailureObserver(
                errorBus = get(),
                authSession = get(),
                scope = get(qualifier = named(APP_SCOPE)),
            )
        }

        // Interim stub — the real platform actual (reading the app's build version) lands in a
        // later task. Announces version/API identity to ConnectionHealthStore's compat check.
        single<ClientIdentity> {
            object : ClientIdentity {
                override val version: String = "0.6.0"
                override val apiVersion: String = EXPECTED_API_VERSION
            }
        }
    }
