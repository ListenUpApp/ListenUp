package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.organize.OrganizeAuthorForm
import com.calypsan.listenup.api.dto.organize.OrganizePreset
import com.calypsan.listenup.api.dto.organize.OrganizeSeriesPrefix
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsEvent
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A session over a state that never changes — the shape specs pass in place of the graph. */
@Suppress("LongParameterList")
fun fixedOrganize(
    state: OrganizeSettingsUiState,
    events: Flow<OrganizeSettingsEvent> = emptyFlow(),
    onPreset: (OrganizePreset) -> Unit = {},
    onSeriesPrefix: (OrganizeSeriesPrefix) -> Unit = {},
    onAuthorForm: (OrganizeAuthorForm) -> Unit = {},
    onSaveRules: () -> Unit = {},
    onOrganize: () -> Unit = {},
    onConfirmOrganize: () -> Unit = {},
    onDismissPreview: () -> Unit = {},
    onDismissReport: () -> Unit = {},
    onResume: () -> Unit = {},
): OpenOrganize =
    {
        OrganizeSession(
            state = MutableStateFlow(state),
            events = events,
            onPreset = onPreset,
            onSeriesPrefix = onSeriesPrefix,
            onAuthorForm = onAuthorForm,
            onSaveRules = onSaveRules,
            onOrganize = onOrganize,
            onConfirmOrganize = onConfirmOrganize,
            onDismissPreview = onDismissPreview,
            onDismissReport = onDismissReport,
            onResume = onResume,
            close = {},
        )
    }
