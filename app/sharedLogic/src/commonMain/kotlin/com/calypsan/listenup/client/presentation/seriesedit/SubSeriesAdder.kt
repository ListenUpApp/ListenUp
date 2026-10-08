package com.calypsan.listenup.client.presentation.seriesedit

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesHierarchy
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Idle timeout before the sheet stops reading the hierarchy. */
private const val STOP_TIMEOUT_MS = 5_000L

/** Where a series the "Add sub-series" sheet lists sits today. */
enum class SubSeriesPlacement {
    /** At the top level — adding it just moves it in. */
    TOP_LEVEL,

    /** Inside another series — adding it moves it out of there, so the sheet asks first. */
    IN_OTHER_PARENT,

    /** Already a sub-series of this one — listed, but not choosable. */
    ALREADY_HERE,
}

/**
 * One series the "Add sub-series" sheet offers.
 *
 * @property bookCount every book in it and its own sub-series.
 * @property subSeriesCount how many series sit directly inside it.
 * @property currentParentName the series it sits in today; null at the top level.
 */
data class SubSeriesCandidateUi(
    val id: String,
    val name: String,
    val coverPath: String?,
    val bookCount: Int,
    val subSeriesCount: Int,
    val placement: SubSeriesPlacement,
    val currentParentName: String?,
) {
    /** Whether choosing this row does anything. */
    val isSelectable: Boolean get() = placement != SubSeriesPlacement.ALREADY_HERE
}

/** A move the sheet asks about first: "Move City Watch out of Discworld into Cosmere?" */
data class PendingSubSeriesMove(
    val seriesId: String,
    val seriesName: String,
    val fromParentName: String,
    val toParentName: String,
)

/**
 * A series that already has the name typed into a "new series" dialog.
 *
 * @property isSelectable whether the dialog may offer to use it instead — false when using it would
 *   form a loop, or change nothing.
 */
data class ExistingSeriesMatch(
    val id: String,
    val name: String,
    val isSelectable: Boolean,
)

/**
 * A "new series" dialog being filled in.
 *
 * @property existing the live series that already has [name], if any. The dialog then offers it
 *   instead of creating a duplicate the server would refuse.
 */
data class NewSeriesDraft(
    val name: String,
    val existing: ExistingSeriesMatch? = null,
) {
    /** Whether the dialog's create button can fire. */
    val canCreate: Boolean get() = name.isNotBlank() && existing == null
}

/**
 * What the "Add sub-series" sheet shows: [Closed], or [Open] with its list.
 *
 * Both carry [isBusy] — true while a change is on its way to the server, which outlives the sheet
 * because choosing closes it — and [error], the last change the server refused. The screen shows the
 * error once, then sends [AddSubSeriesEvent.ErrorDismissed].
 */
sealed interface AddSubSeriesUiState {
    val isBusy: Boolean
    val error: AppError?

    /** The sheet is not showing. */
    data class Closed(
        override val isBusy: Boolean = false,
        override val error: AppError? = null,
    ) : AddSubSeriesUiState

    /**
     * The sheet is open.
     *
     * @property parentName the series sub-series are added to.
     * @property candidates the series it may take, filtered by [query]: never itself or a series above it.
     * @property pendingMove a move waiting for the reader to confirm.
     * @property newSeries the "New series…" dialog, while it is open.
     */
    data class Open(
        val parentName: String,
        val query: String,
        val candidates: List<SubSeriesCandidateUi>,
        val pendingMove: PendingSubSeriesMove?,
        val newSeries: NewSeriesDraft?,
        override val isBusy: Boolean,
        override val error: AppError?,
    ) : AddSubSeriesUiState
}

/** What the reader does in the "Add sub-series" sheet. */
sealed interface AddSubSeriesEvent {
    /** Open the sheet. */
    data object Opened : AddSubSeriesEvent

    /** Close the sheet without choosing. */
    data object Dismissed : AddSubSeriesEvent

    /** The search text changed. */
    data class QueryChanged(
        val query: String,
    ) : AddSubSeriesEvent

    /** The reader chose [seriesId]. A series inside another parent asks first. */
    data class Chosen(
        val seriesId: String,
    ) : AddSubSeriesEvent

    /** The reader confirmed the pending move. */
    data object MoveConfirmed : AddSubSeriesEvent

    /** The reader backed out of the pending move. */
    data object MoveCancelled : AddSubSeriesEvent

    /** Open the "New series…" dialog. */
    data object NewSeriesStarted : AddSubSeriesEvent

    /** The new series' name changed. */
    data class NewSeriesNameChanged(
        val name: String,
    ) : AddSubSeriesEvent

    /** Close the "New series…" dialog. */
    data object NewSeriesDismissed : AddSubSeriesEvent

    /** Create the new series inside this one. */
    data object NewSeriesConfirmed : AddSubSeriesEvent

    /** The screen has shown [AddSubSeriesUiState.error]. */
    data object ErrorDismissed : AddSubSeriesEvent
}

/** The sheet's own inputs; everything else is read from the hierarchy. */
private data class AdderInputs(
    val isVisible: Boolean = false,
    val query: String = "",
    val pendingMoveId: String? = null,
    val newSeriesName: String? = null,
    val isBusy: Boolean = false,
    val error: AppError? = null,
)

/**
 * "Add sub-series": put an existing series inside [parentId], or create a new one there. Shared by
 * the series page and the series editor, which read the same sheet.
 *
 * Every change goes to the server and returns through sync — nothing is written here. A change the
 * server refuses goes to the [errorBus] and into [AddSubSeriesUiState.error]. One change at a time.
 */
internal class SubSeriesAdder(
    private val scope: CoroutineScope,
    private val errorBus: ErrorBus,
    hierarchy: Flow<SeriesHierarchy>,
    private val parentId: () -> String?,
    private val createSeries: suspend (String, SeriesId?) -> AppResult<SeriesId>,
    private val setParent: suspend (SeriesId, SeriesId?) -> AppResult<Unit>,
) {
    private val inputs = MutableStateFlow(AdderInputs())
    private var latest: SeriesHierarchy = SeriesHierarchy.Empty

    /** What the sheet shows. Reads the hierarchy only while the sheet is open. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<AddSubSeriesUiState> =
        inputs
            .map { it.isVisible }
            .distinctUntilChanged()
            .flatMapLatest { visible ->
                if (!visible) {
                    inputs.map<AdderInputs, AddSubSeriesUiState> { closedInputs ->
                        AddSubSeriesUiState.Closed(
                            isBusy = closedInputs.isBusy,
                            error = closedInputs.error,
                        )
                    }
                } else {
                    combine(inputs, hierarchy) { input, tree ->
                        latest = tree
                        render(input, tree)
                    }
                }
            }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AddSubSeriesUiState.Closed())

    fun onEvent(event: AddSubSeriesEvent) {
        when (event) {
            AddSubSeriesEvent.Opened -> inputs.update { AdderInputs(isVisible = true, isBusy = it.isBusy) }
            AddSubSeriesEvent.Dismissed -> inputs.update { AdderInputs(isBusy = it.isBusy, error = it.error) }
            is AddSubSeriesEvent.QueryChanged -> inputs.update { it.copy(query = event.query) }
            is AddSubSeriesEvent.Chosen -> choose(event.seriesId)
            AddSubSeriesEvent.MoveConfirmed -> inputs.value.pendingMoveId?.let(::move)
            AddSubSeriesEvent.MoveCancelled -> inputs.update { it.copy(pendingMoveId = null) }
            AddSubSeriesEvent.NewSeriesStarted -> inputs.update { it.copy(newSeriesName = "") }
            is AddSubSeriesEvent.NewSeriesNameChanged -> inputs.update { it.copy(newSeriesName = event.name) }
            AddSubSeriesEvent.NewSeriesDismissed -> inputs.update { it.copy(newSeriesName = null) }
            AddSubSeriesEvent.NewSeriesConfirmed -> createNew()
            AddSubSeriesEvent.ErrorDismissed -> inputs.update { it.copy(error = null) }
        }
    }

    private fun render(
        input: AdderInputs,
        tree: SeriesHierarchy,
    ): AddSubSeriesUiState {
        val parent = parentId()?.let(tree::byId)
        val all = candidates(tree, parent)
        val pending = input.pendingMoveId?.let { id -> all.firstOrNull { it.id == id } }
        return AddSubSeriesUiState.Open(
            parentName = parent?.name.orEmpty(),
            query = input.query,
            candidates =
                all.filter { input.query.isBlank() || it.name.contains(input.query.trim(), ignoreCase = true) },
            pendingMove =
                pending?.currentParentName?.let { from ->
                    PendingSubSeriesMove(
                        seriesId = pending.id,
                        seriesName = pending.name,
                        fromParentName = from,
                        toParentName = parent?.name.orEmpty(),
                    )
                },
            newSeries =
                input.newSeriesName?.let { name ->
                    NewSeriesDraft(
                        name = name,
                        existing = existingMatch(tree, name, all),
                    )
                },
            isBusy = input.isBusy,
            error = input.error,
        )
    }

    /** The series already called [name], if any, and whether it is one of the [candidates] the sheet can offer. */
    private fun existingMatch(
        tree: SeriesHierarchy,
        name: String,
        candidates: List<SubSeriesCandidateUi>,
    ): ExistingSeriesMatch? =
        tree.findByName(name)?.let { match ->
            ExistingSeriesMatch(
                id = match.id.value,
                name = match.name,
                isSelectable = candidates.any { it.id == match.id.value && it.isSelectable },
            )
        }

    /** Every series but [parent] and the series above it (choosing those could only form a loop). */
    private fun candidates(
        tree: SeriesHierarchy,
        parent: Series?,
    ): List<SubSeriesCandidateUi> {
        val parentId = parent?.run { id.value } ?: return emptyList()
        val excluded = tree.ancestorsOf(parentId).mapTo(hashSetOf(parentId)) { it.id.value }
        return tree.series
            .filter { it.id.value !in excluded }
            .map { series ->
                val id = series.id.value
                val currentParent = tree.ancestorsOf(id).lastOrNull()
                SubSeriesCandidateUi(
                    id = id,
                    name = series.name,
                    coverPath = series.coverPath,
                    bookCount = tree.bookCount(id),
                    subSeriesCount = tree.childrenOf(id).size,
                    placement =
                        when (currentParent?.run { this.id.value }) {
                            null -> SubSeriesPlacement.TOP_LEVEL
                            parentId -> SubSeriesPlacement.ALREADY_HERE
                            else -> SubSeriesPlacement.IN_OTHER_PARENT
                        },
                    currentParentName = currentParent?.name,
                )
            }.sortedWith(compareBy<SubSeriesCandidateUi> { !it.isSelectable }.thenBy { it.name.lowercase() })
    }

    private fun choose(seriesId: String) {
        val parent = parentId()?.let(latest::byId) ?: return
        val candidate = candidates(latest, parent).firstOrNull { it.id == seriesId } ?: return
        when (candidate.placement) {
            SubSeriesPlacement.ALREADY_HERE -> Unit
            SubSeriesPlacement.IN_OTHER_PARENT -> inputs.update { it.copy(pendingMoveId = seriesId) }
            SubSeriesPlacement.TOP_LEVEL -> move(seriesId)
        }
    }

    private fun move(seriesId: String) {
        val parent = parentId() ?: return
        send { setParent(SeriesId(seriesId), SeriesId(parent)) }
    }

    private fun createNew() {
        val parent = parentId() ?: return
        val name =
            inputs.value.newSeriesName
                ?.trim()
                .orEmpty()
        if (name.isEmpty() || latest.findByName(name) != null) return
        send {
            createSeries(
                name,
                SeriesId(parent),
            ).let { if (it is AppResult.Failure) it else AppResult.Success(Unit) }
        }
    }

    /** Closes the sheet and sends one change; a refusal reaches the error bus and [state]. */
    private fun send(write: suspend () -> AppResult<Unit>) {
        if (inputs.value.isBusy) return
        inputs.value = AdderInputs(isBusy = true)
        scope.launch {
            when (val result = write()) {
                is AppResult.Success -> {
                    inputs.update { it.copy(isBusy = false) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    inputs.update { it.copy(isBusy = false, error = result.error) }
                }
            }
        }
    }
}
