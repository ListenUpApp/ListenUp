package com.calypsan.listenup.client.presentation.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.DownloadPhase
import com.calypsan.listenup.client.domain.model.downloadPhaseOf
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Where every book's download stands, keyed by book id, for surfaces that mark many books at once (the Library grid's
 * cover badges). A book with no download has no entry, so it draws no badge.
 *
 * The phases come from the one shared rule, [downloadPhaseOf], over the aggregated statuses and whether Wi-Fi-only is
 * holding downloads back right now, so a badge always agrees with the book's own screen.
 */
class DownloadPhasesViewModel(
    downloadRepository: DownloadRepository,
    localPreferences: LocalPreferences,
    networkMonitor: NetworkMonitor,
) : ViewModel() {
    /** Each downloaded or downloading book's phase; books with nothing downloaded are absent. */
    val phases: StateFlow<Map<String, DownloadPhase>> =
        combine(
            downloadRepository.observeAllStatuses(),
            localPreferences.wifiOnlyDownloads,
            networkMonitor.onUnmeteredNetworkFlow,
        ) { statuses, wifiOnly, unmetered ->
            val wifiBlocked = wifiOnly && !unmetered
            statuses
                .mapValues { (_, status) -> downloadPhaseOf(status, wifiBlocked) }
                .filterValues { it != DownloadPhase.NotDownloaded }
        }.stateIn(viewModelScope, WhileSubscribedForUi, emptyMap())
}
