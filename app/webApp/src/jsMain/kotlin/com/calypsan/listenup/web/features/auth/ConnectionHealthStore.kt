package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.web.nav.RouteSession
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.connection.ConnectionHealthUi
import com.calypsan.listenup.client.presentation.connection.ConnectionHealthViewModel
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/** An open connection-health projection, plus the teardown for it. */
class ConnectionHealthSession(
    val state: StateFlow<ConnectionHealthUi>,
    val onDismiss: () -> Unit,
    override val close: () -> Unit,
) : RouteSession

/**
 * How the shell learns whether anything is wrong with its connection.
 *
 * Production resolves the shared [ConnectionHealthViewModel] ([graphConnectionHealth]); specs hand
 * over a fixed projection.
 */
typealias OpenConnectionHealth = () -> ConnectionHealthSession

/**
 * The production source: the shared [ConnectionHealthViewModel].
 *
 * ⛔ This is the *only* thing that decides whether the shell shows a banner, which is the point of
 * wiring it. Web previously derived its session-lapsed banner straight from
 * `AuthState.SessionLapsed`, and `ConnectionHealthStore` derives its own `SessionExpired` from
 * exactly the same flow — so keeping both would have rendered two banners for one fact the moment
 * this ViewModel arrived. One source, one projection, as the store's own KDoc requires.
 *
 * `signIn()` is deliberately not exposed. It emits a navigate-to-sign-in event for clients whose
 * sign-in is a screen; web answers a lapse with a sheet over the page the reader is already on, so
 * the banner keeps its own affordance rather than routing away from their place.
 */
fun graphConnectionHealth(koin: Koin): OpenConnectionHealth =
    {
        val viewModel = koin.get<ConnectionHealthViewModel>()
        val store = ViewModelStore().apply { put("connection-health", viewModel) }
        ConnectionHealthSession(
            state = viewModel.state,
            onDismiss = viewModel::dismiss,
            close = store::clear,
        )
    }
