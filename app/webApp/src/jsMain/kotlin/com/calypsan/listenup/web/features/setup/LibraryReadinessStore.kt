package com.calypsan.listenup.web.features.setup

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.startup.AppStartupViewModel
import com.calypsan.listenup.client.presentation.startup.LibraryReadiness
import com.calypsan.listenup.web.nav.RouteSession
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * Whether the signed-in app may mount yet, and the gestures that move it on.
 *
 * [readiness] is the same sealed answer Android's and iOS's startup gates switch on, so the browser
 * no longer hand-derives it from the setup wizard's state.
 */
class LibraryReadinessSession(
    val readiness: StateFlow<LibraryReadiness>,
    /** Ask the server again after a check that failed with nothing local to fall back on. */
    val onRetry: () -> Unit,
    /** The setup wizard finished (or found nothing to do); clear the needs-setup answer. */
    val onSetupComplete: () -> Unit,
    /** Leave a stalled "Building your library" screen for the partial library. */
    val onContinueToPartialLibrary: () -> Unit,
    override val close: () -> Unit,
) : RouteSession

/** How the gate gets its readiness. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenLibraryReadiness = () -> LibraryReadinessSession

/**
 * The production readiness: the shared [AppStartupViewModel].
 *
 * Opened by the signed-in branch, which stays mounted from a lapsed session through the sign-in that
 * revives it — so the ViewModel sees that transition and re-runs a check it could only answer
 * provisionally, rather than leaving a stale failure up.
 */
fun graphLibraryReadiness(koin: Koin): OpenLibraryReadiness =
    {
        val viewModel = koin.get<AppStartupViewModel>()
        val store = ViewModelStore().apply { put("library-readiness", viewModel) }
        LibraryReadinessSession(
            readiness = viewModel.readiness,
            onRetry = viewModel::retryLibrarySetupCheck,
            onSetupComplete = viewModel::onLibrarySetupComplete,
            onContinueToPartialLibrary = viewModel::onContinueToPartialLibrary,
            close = store::clear,
        )
    }
