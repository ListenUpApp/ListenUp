package com.calypsan.listenup.web.features.contributormetadata

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataEvent
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin

/**
 * An open Contributor Metadata session — the wizard that finds a person on Audible and takes their
 * biography and photo.
 *
 * Narrower than the book wizard's session because the operation is narrower: there is no per-field
 * selection here. The server applies the profile as a unit, so the decision is which person, not
 * which fields.
 */
class ContributorMetadataSession(
    val state: StateFlow<ContributorMetadataUiState>,
    val events: Flow<ContributorMetadataEvent>,
    val onQuery: (String) -> Unit,
    val onRegion: (MetadataLocale) -> Unit,
    val onSearch: () -> Unit,
    val onSelectCandidate: (MetadataContributorHit) -> Unit,
    val onClearSelection: () -> Unit,
    val onApply: () -> Unit,
    val close: () -> Unit,
)

/** How the page gets its state. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenContributorMetadata = (contributorId: String) -> ContributorMetadataSession

/**
 * The production source: the shared [ContributorMetadataViewModel], pointed at one contributor.
 *
 * `init` fires here. It loads the person from Room, seeds the query with their name and searches —
 * so the reader usually arrives on a page that has already found the candidates.
 */
fun graphContributorMetadata(koin: Koin): OpenContributorMetadata =
    { contributorId ->
        val viewModel = koin.get<ContributorMetadataViewModel>()
        val store = ViewModelStore().apply { put(contributorId, viewModel) }
        viewModel.init(contributorId)
        ContributorMetadataSession(
            state = viewModel.state,
            events = viewModel.events,
            onQuery = viewModel::updateQuery,
            onRegion = viewModel::changeRegion,
            onSearch = viewModel::search,
            onSelectCandidate = viewModel::selectCandidate,
            onClearSelection = viewModel::clearSelection,
            onApply = viewModel::apply,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedContributorMetadata(
    state: ContributorMetadataUiState,
    events: Flow<ContributorMetadataEvent> = emptyFlow(),
    onQuery: (String) -> Unit = {},
    onRegion: (MetadataLocale) -> Unit = {},
    onSearch: () -> Unit = {},
    onSelectCandidate: (MetadataContributorHit) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onApply: () -> Unit = {},
): OpenContributorMetadata =
    {
        ContributorMetadataSession(
            state = MutableStateFlow(state),
            events = events,
            onQuery = onQuery,
            onRegion = onRegion,
            onSearch = onSearch,
            onSelectCandidate = onSelectCandidate,
            onClearSelection = onClearSelection,
            onApply = onApply,
            close = {},
        )
    }
