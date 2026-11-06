package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.web.nav.RouteSession
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.dto.organize.OrganizeAuthorForm
import com.calypsan.listenup.api.dto.organize.OrganizePreset
import com.calypsan.listenup.api.dto.organize.OrganizeSeriesPrefix
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsEvent
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsUiState
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/** An open file-organizer session. */
class OrganizeSession(
    val state: StateFlow<OrganizeSettingsUiState>,
    val events: Flow<OrganizeSettingsEvent>,
    val onPreset: (OrganizePreset) -> Unit,
    val onSeriesPrefix: (OrganizeSeriesPrefix) -> Unit,
    val onAuthorForm: (OrganizeAuthorForm) -> Unit,
    val onSaveRules: () -> Unit,
    val onOrganize: () -> Unit,
    val onConfirmOrganize: () -> Unit,
    val onDismissPreview: () -> Unit,
    val onDismissReport: () -> Unit,
    val onResume: () -> Unit,
    override val close: () -> Unit,
) : RouteSession

/** How the organizer screen gets its state. */
typealias OpenOrganize = () -> OrganizeSession

/** The production source: the shared [OrganizeSettingsViewModel]. */
fun graphOrganize(koin: Koin): OpenOrganize =
    {
        val viewModel = koin.get<OrganizeSettingsViewModel>()
        val store = ViewModelStore().apply { put("organize", viewModel) }
        OrganizeSession(
            state = viewModel.state,
            events = viewModel.events,
            onPreset = viewModel::setPreset,
            onSeriesPrefix = viewModel::setSeriesPrefix,
            onAuthorForm = viewModel::setAuthorForm,
            onSaveRules = viewModel::saveRules,
            onOrganize = viewModel::organize,
            onConfirmOrganize = viewModel::confirmOrganize,
            onDismissPreview = viewModel::dismissPreview,
            onDismissReport = viewModel::dismissRunReport,
            onResume = viewModel::resumeAfterFailure,
            close = store::clear,
        )
    }
