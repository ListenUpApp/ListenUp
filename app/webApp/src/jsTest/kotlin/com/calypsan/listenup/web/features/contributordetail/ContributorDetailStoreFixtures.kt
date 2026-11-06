package com.calypsan.listenup.web.features.contributordetail

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailNavAction
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedContributorDetail(
    state: ContributorDetailUiState,
    onConfirmDelete: () -> Unit = {},
    onDismissDeleteError: () -> Unit = {},
    onAdjustPhoto: (ImageFrame) -> Unit = {},
    navActions: Flow<ContributorDetailNavAction> = emptyFlow(),
): OpenContributorDetail =
    {
        ContributorDetailSession(
            state = MutableStateFlow(state),
            onConfirmDelete = onConfirmDelete,
            onDismissDeleteError = onDismissDeleteError,
            onAdjustPhoto = onAdjustPhoto,
            navActions = navActions,
            close = {},
        )
    }
