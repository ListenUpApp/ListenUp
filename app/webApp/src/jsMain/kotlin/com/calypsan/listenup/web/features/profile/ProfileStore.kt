package com.calypsan.listenup.web.features.profile

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import com.calypsan.listenup.client.presentation.profile.UserProfileViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/** An open profile, the retry it accepts, and the teardown for it. */
class ProfileSession(
    val state: StateFlow<UserProfileUiState>,
    val onRetry: () -> Unit,
    val close: () -> Unit,
)

/** How the page gets its profile. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenProfile = (userId: String) -> ProfileSession

/**
 * The production source: the shared [UserProfileViewModel], pointed at [userId].
 *
 * `loadProfile` is a no-op for a repeat of the same id, which is why the session is keyed on the id
 * at the call site rather than relying on this to notice — see `profileState`.
 */
fun graphProfile(koin: Koin): OpenProfile =
    { userId ->
        val viewModel = koin.get<UserProfileViewModel>()
        val store = ViewModelStore().apply { put(userId, viewModel) }
        viewModel.loadProfile(userId)
        ProfileSession(
            state = viewModel.state,
            onRetry = { viewModel.loadProfile(userId, forceRefresh = true) },
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedProfile(
    state: UserProfileUiState,
    onRetry: () -> Unit = {},
): OpenProfile = { ProfileSession(MutableStateFlow(state), onRetry, close = {}) }
