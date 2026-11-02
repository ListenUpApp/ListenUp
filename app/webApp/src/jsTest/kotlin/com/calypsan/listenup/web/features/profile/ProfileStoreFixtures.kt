package com.calypsan.listenup.web.features.profile

import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedProfile(
    state: UserProfileUiState,
    onRetry: () -> Unit = {},
): OpenProfile = { ProfileSession(MutableStateFlow(state), onRetry, close = {}) }
