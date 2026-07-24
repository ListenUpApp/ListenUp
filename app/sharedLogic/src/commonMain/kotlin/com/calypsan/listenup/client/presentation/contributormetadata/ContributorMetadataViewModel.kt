package com.calypsan.listenup.client.presentation.contributormetadata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Hard ceiling on a single metadata RPC call. Same rationale as the book wizard's
 * [com.calypsan.listenup.client.presentation.metadata.MetadataViewModel]: metadata lookups hit
 * external providers and can be genuinely slow, so this is generous — its only job is to
 * guarantee that a black-hole WebSocket eventually surfaces as a visible error instead of an
 * infinite spinner. Never-Stranded: the user always gets an outcome.
 */
private val CONTRIBUTOR_RPC_TIMEOUT = 30.seconds

private const val SEARCH_TIMEOUT_MESSAGE = "Search timed out. Check your connection and try again."
private const val SEARCH_FAILED_MESSAGE = "Something went wrong while searching. Please try again."
private const val PREVIEW_TIMEOUT_MESSAGE = "Loading the profile timed out. Check your connection and try again."
private const val PREVIEW_FAILED_MESSAGE = "Something went wrong while loading the profile. Please try again."

/** Contributor context carried across wizard phases: the target row and its current values for the compare UI. */
data class ContributorContext(
    /** The local contributor being enriched. */
    val contributorId: String,
    /** The contributor's current values (name/bio/photo), loaded from Room; null until loaded. */
    val current: Contributor?,
)

/**
 * UI state for the contributor metadata match wizard.
 *
 * Three top-level phases, mirroring the book wizard's
 * [com.calypsan.listenup.client.presentation.metadata.MetadataUiState]:
 * - [Idle] — no contributor loaded yet (pre-[ContributorMetadataViewModel.init]).
 * - [Search] — contributor loaded, user is searching / browsing hits.
 * - [Preview] — user picked a hit; loading, displaying, or missing the profile.
 *
 * Each phase carries a single-axis sub-state sealed hierarchy; the current [region] is lifted to
 * the interface because it persists across phase transitions.
 */
sealed interface ContributorMetadataUiState {
    /** The Audible/Audnexus region every lookup in this flow targets. */
    val region: MetadataLocale

    /** No contributor loaded yet; pre-[ContributorMetadataViewModel.init] placeholder. */
    data class Idle(
        override val region: MetadataLocale = MetadataLocale.DEFAULT,
    ) : ContributorMetadataUiState

    /** Contributor loaded; user is editing the search query and browsing hits. */
    data class Search(
        override val region: MetadataLocale,
        val context: ContributorContext,
        val query: String,
        val loadState: ContributorSearchLoadState,
    ) : ContributorMetadataUiState

    /**
     * User picked a [match]; the profile is loading, ready, missing, or failed. [searchResults] is
     * retained so [ContributorMetadataViewModel.clearSelection] can return to [Search] without
     * re-issuing the search.
     */
    data class Preview(
        override val region: MetadataLocale,
        val context: ContributorContext,
        val query: String,
        val searchResults: List<MetadataContributorHit>,
        val match: MetadataContributorHit,
        val loadState: ContributorPreviewLoadState,
    ) : ContributorMetadataUiState
}

/** Sub-state of [ContributorMetadataUiState.Search]. */
sealed interface ContributorSearchLoadState {
    /** No search issued yet. */
    data object Idle : ContributorSearchLoadState

    /** A search is in flight. */
    data object InFlight : ContributorSearchLoadState

    /** The search returned [results] (deduped server-side). */
    data class Loaded(
        val results: List<MetadataContributorHit>,
    ) : ContributorSearchLoadState

    /** The search failed; [message] is shown in-line. */
    data class Failed(
        val message: String,
    ) : ContributorSearchLoadState
}

/** Sub-state of [ContributorMetadataUiState.Preview]. */
sealed interface ContributorPreviewLoadState {
    /** The profile fetch is in flight. */
    data object Loading : ContributorPreviewLoadState

    /**
     * The profile is ready to preview and apply. [isApplying]/[applyError] overlay the ready data
     * while the apply mutation is in flight / has just failed — they never replace the preview.
     */
    data class Ready(
        val profile: MetadataContributorProfile,
        val isApplying: Boolean,
        val applyError: String?,
    ) : ContributorPreviewLoadState

    /**
     * The catalog answered but has no usable profile data for this ASIN in the current region —
     * Audnexus returns an HTTP-200 empty shell for cross-region fetches. An honest miss: the UI
     * offers a region switch instead of a blank preview above a live Apply button.
     */
    data object Missing : ContributorPreviewLoadState

    /** The profile fetch failed; [message] is shown in-line. */
    data class Failed(
        val message: String,
    ) : ContributorPreviewLoadState
}

/** One-shot outcomes the contributor metadata wizard emits. */
sealed interface ContributorMetadataEvent {
    /** Apply succeeded; the route should navigate away. */
    data object MetadataApplied : ContributorMetadataEvent
}

/**
 * The (contributor, match, region) triple an in-flight preview load or apply mutation was issued
 * against — captured before the async work starts so the eventual outcome can be checked against
 * whatever the state has moved on to, never against itself.
 */
private data class PreviewTarget(
    val contributorId: String,
    val asin: String,
    val region: MetadataLocale,
)

/** Whether this [Preview][ContributorMetadataUiState.Preview] is still the one [target] was issued against. */
private fun ContributorMetadataUiState.Preview.matches(target: PreviewTarget): Boolean =
    context.contributorId == target.contributorId &&
        match.asin == target.asin &&
        region == target.region

/**
 * ViewModel for the contributor metadata search-and-match wizard.
 *
 * Command-driven, in the image of the book wizard's
 * [com.calypsan.listenup.client.presentation.metadata.MetadataViewModel]: all transitions are
 * explicit method calls, every RPC is capped by [CONTRIBUTOR_RPC_TIMEOUT], and every async result
 * lands through a stale guard keyed on the still-current query / `asin+region`, so a switched
 * match or region can never receive a late result.
 *
 * Apply calls [MetadataRepository.applyContributorMetadata] directly — the server applies
 * asin + biography + photo (never the name; there is no per-field selection, matching both the
 * server's behavior and Audiobookshelf's). Success is a one-shot [ContributorMetadataEvent].
 */
class ContributorMetadataViewModel(
    private val contributorRepository: ContributorRepository,
    private val metadataRepository: MetadataRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<ContributorMetadataUiState>
        field = MutableStateFlow<ContributorMetadataUiState>(ContributorMetadataUiState.Idle())

    private val eventChannel = Channel<ContributorMetadataEvent>(Channel.BUFFERED)

    /** One-shot wizard outcomes; collect from the route to drive navigation. */
    val events: Flow<ContributorMetadataEvent> = eventChannel.receiveAsFlow()

    /**
     * Monotonic apply-attempt generation. [contributorId]/[asin]/[region] alone can't distinguish two
     * attempts at the SAME target — e.g. apply → clearSelection → re-select the same candidate in the
     * same region produces a fresh [ContributorPreviewLoadState.Ready] with an identity indistinguishable
     * from the abandoned attempt's. [apply] captures the value it incremented to; any call that abandons
     * an in-flight apply ([clearSelection], [changeRegion], [selectCandidate]) invalidates it by
     * incrementing again, so a late-arriving outcome from a superseded attempt is dropped even when its
     * identity still matches. The VM is main-thread confined (per [ViewModel] contract), so a plain `Int`
     * is sufficient — no atomics.
     */
    private var applyAttempt = 0

    /**
     * Initialize the wizard for a contributor: synchronously enter [ContributorMetadataUiState.Search]
     * (blank query, idle results), then load the contributor from Room, seed the query with their
     * name, and auto-search. The auto-search is skipped if the state has already left the Search
     * phase (e.g. the preview route called [selectAsin] right after init).
     */
    fun init(contributorId: String) {
        state.value =
            ContributorMetadataUiState.Search(
                region = state.value.region,
                context = ContributorContext(contributorId = contributorId, current = null),
                query = "",
                loadState = ContributorSearchLoadState.Idle,
            )

        viewModelScope.launch {
            val contributor = contributorRepository.observeById(contributorId).first()
            state.update { latest ->
                when (latest) {
                    is ContributorMetadataUiState.Search -> {
                        latest.copy(
                            context = latest.context.copy(current = contributor),
                            query = latest.query.ifBlank { contributor?.name.orEmpty() },
                        )
                    }

                    is ContributorMetadataUiState.Preview -> {
                        latest.copy(context = latest.context.copy(current = contributor))
                    }

                    is ContributorMetadataUiState.Idle -> {
                        latest
                    }
                }
            }
            if (!contributor?.name.isNullOrBlank() && state.value is ContributorMetadataUiState.Search) {
                search()
            }
        }
    }

    /** Update the search query while in the [ContributorMetadataUiState.Search] phase. */
    fun updateQuery(query: String) {
        state.update { current ->
            if (current is ContributorMetadataUiState.Search) current.copy(query = query) else current
        }
    }

    /**
     * Change the region and immediately reflect it: in preview, re-fetch the open profile in the
     * new region; in search, re-run the query so results update without a manual re-submit.
     *
     * Abandons any in-flight [apply] — see [applyAttempt].
     */
    fun changeRegion(region: MetadataLocale) {
        applyAttempt++
        when (val current = state.value) {
            is ContributorMetadataUiState.Idle -> {
                state.value = current.copy(region = region)
            }

            is ContributorMetadataUiState.Search -> {
                state.value = current.copy(region = region)
                search()
            }

            is ContributorMetadataUiState.Preview -> {
                // Region and loadState land in the SAME assignment — the new region must never be
                // visible paired with the old region's Ready profile, even for a single frame.
                val next = current.copy(region = region, loadState = ContributorPreviewLoadState.Loading)
                state.value = next
                loadPreview(PreviewTarget(next.context.contributorId, next.match.asin, region))
            }
        }
    }

    /** Execute a contributor search with the current query and region. */
    fun search() {
        val current = state.value as? ContributorMetadataUiState.Search ?: return
        val query = current.query.trim()
        if (query.isBlank()) return

        state.value = current.copy(loadState = ContributorSearchLoadState.InFlight)

        viewModelScope.launch {
            try {
                val result =
                    withTimeout(CONTRIBUTOR_RPC_TIMEOUT) {
                        metadataRepository.searchContributorMetadata(query, current.region)
                    }
                when (result) {
                    is AppResult.Success -> {
                        state.update { latest ->
                            if (latest is ContributorMetadataUiState.Search && latest.query.trim() == query) {
                                latest.copy(loadState = ContributorSearchLoadState.Loaded(result.data))
                            } else {
                                latest
                            }
                        }
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        logger.error { "Contributor search failed: ${result.error.message}" }
                        setSearchFailed(query, result.error.message)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logger.error(e) { "Contributor search timed out for \"$query\"" }
                setSearchFailed(query, SEARCH_TIMEOUT_MESSAGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error(e) { "Contributor search failed unexpectedly for \"$query\"" }
                setSearchFailed(query, SEARCH_FAILED_MESSAGE)
            }
        }
    }

    /** Project a [ContributorSearchLoadState.Failed] onto the still-current search, ignoring a superseded query. */
    private fun setSearchFailed(
        query: String,
        message: String,
    ) {
        state.update { latest ->
            if (latest is ContributorMetadataUiState.Search && latest.query.trim() == query) {
                latest.copy(loadState = ContributorSearchLoadState.Failed(message))
            } else {
                latest
            }
        }
    }

    /**
     * Select a search hit and transition to the preview phase.
     *
     * Abandons any in-flight [apply] — see [applyAttempt].
     */
    fun selectCandidate(result: MetadataContributorHit) {
        applyAttempt++
        val current = state.value
        val region: MetadataLocale
        val context: ContributorContext
        val query: String
        val baseResults: List<MetadataContributorHit>

        when (current) {
            is ContributorMetadataUiState.Search -> {
                region = current.region
                context = current.context
                query = current.query
                baseResults = (current.loadState as? ContributorSearchLoadState.Loaded)?.results.orEmpty()
            }

            is ContributorMetadataUiState.Preview -> {
                region = current.region
                context = current.context
                query = current.query
                baseResults = current.searchResults
            }

            is ContributorMetadataUiState.Idle -> {
                return
            }
        }

        state.value =
            ContributorMetadataUiState.Preview(
                region = region,
                context = context,
                query = query,
                searchResults = baseResults,
                match = result,
                loadState = ContributorPreviewLoadState.Loading,
            )

        loadPreview(PreviewTarget(context.contributorId, result.asin, region))
    }

    /**
     * Select a hit by ASIN alone — the deep-link / nav-route entry point (the route carries only
     * the ASIN; the display name is backfilled from the fetched profile).
     */
    fun selectAsin(asin: String) {
        selectCandidate(MetadataContributorHit(asin = asin, name = ""))
    }

    /**
     * Clear the current match and return to the search phase with the retained results.
     *
     * Abandons any in-flight [apply] — see [applyAttempt].
     */
    fun clearSelection() {
        applyAttempt++
        val current = state.value as? ContributorMetadataUiState.Preview ?: return
        state.value =
            ContributorMetadataUiState.Search(
                region = current.region,
                context = current.context,
                query = current.query,
                loadState =
                    if (current.searchResults.isEmpty()) {
                        ContributorSearchLoadState.Idle
                    } else {
                        ContributorSearchLoadState.Loaded(current.searchResults)
                    },
            )
    }

    /**
     * Apply the previewed profile to the contributor. Only legal from
     * [ContributorPreviewLoadState.Ready] — a missing or failed preview has no Apply affordance.
     *
     * Two independent guards protect the completion path against a stale outcome: the identity
     * (contributor, asin, region), and [applyAttempt]. Identity alone lands correctly when the
     * user switches to a *different* candidate/region while this apply is in flight, but it can't
     * distinguish a fresh [ContributorPreviewLoadState.Ready] from a later re-select of the SAME
     * candidate/region — [applyAttempt] closes that gap. Both the [Ready] overlay and the
     * [ContributorMetadataEvent.MetadataApplied] send are gated on both, mirroring
     * [setPreviewFailed]'s stale guard.
     */
    fun apply() {
        val preview = state.value as? ContributorMetadataUiState.Preview ?: return
        val ready = preview.loadState as? ContributorPreviewLoadState.Ready ?: return
        if (ready.isApplying) return

        val target = PreviewTarget(preview.context.contributorId, preview.match.asin, preview.region)
        val attempt = ++applyAttempt

        updateReady(target) { it.copy(isApplying = true, applyError = null) }

        viewModelScope.launch {
            when (
                val result =
                    metadataRepository.applyContributorMetadata(
                        contributorId = ContributorId(target.contributorId),
                        asin = target.asin,
                        region = target.region,
                    )
            ) {
                is AppResult.Success -> {
                    if (attempt == applyAttempt) {
                        val stillCurrent = updateReady(target) { it.copy(isApplying = false, applyError = null) }
                        if (stillCurrent) {
                            eventChannel.trySend(ContributorMetadataEvent.MetadataApplied)
                        }
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to apply contributor metadata: ${result.error.message}" }
                    if (attempt == applyAttempt) {
                        updateReady(target) { it.copy(isApplying = false, applyError = result.error.message) }
                    }
                }
            }
        }
    }

    /** Reset back to [ContributorMetadataUiState.Idle], keeping the chosen region. */
    fun reset() {
        state.value = ContributorMetadataUiState.Idle(region = state.value.region)
    }

    private fun loadPreview(target: PreviewTarget) {
        viewModelScope.launch {
            try {
                val result =
                    withTimeout(CONTRIBUTOR_RPC_TIMEOUT) {
                        metadataRepository.getContributorMetadata(target.asin, target.region)
                    }
                when (result) {
                    is AppResult.Success -> {
                        projectPreviewResult(target, result.data)
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        logger.error { "Failed to load contributor profile: ${result.error.message}" }
                        setPreviewFailed(target, result.error.message)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logger.error(e) { "Contributor profile load timed out for ${target.asin}" }
                setPreviewFailed(target, PREVIEW_TIMEOUT_MESSAGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error(e) { "Contributor profile load failed unexpectedly for ${target.asin}" }
                setPreviewFailed(target, PREVIEW_FAILED_MESSAGE)
            }
        }
    }

    /**
     * Project a successful [loadPreview] fetch onto the still-current preview, ignoring a preview
     * that has since moved on to a different contributor/match/region — the same stale guard
     * [setPreviewFailed] uses for the failure path.
     */
    private fun projectPreviewResult(
        target: PreviewTarget,
        profile: MetadataContributorProfile?,
    ) {
        state.update { latest ->
            if (latest !is ContributorMetadataUiState.Preview || !latest.matches(target)) {
                return@update latest
            }
            if (profile == null || profile.isEmptyShell()) {
                // Decision (c): an HTTP-200 shell with no bio AND no photo is an
                // honest MISS — Audnexus localizes profile content per region.
                latest.copy(loadState = ContributorPreviewLoadState.Missing)
            } else {
                latest.copy(
                    match =
                        if (latest.match.name.isBlank()) {
                            latest.match.copy(name = profile.name)
                        } else {
                            latest.match
                        },
                    loadState =
                        ContributorPreviewLoadState.Ready(
                            profile = profile,
                            isApplying = false,
                            applyError = null,
                        ),
                )
            }
        }
    }

    /** A profile with neither biography nor photo is an empty regional shell — nothing to preview or apply. */
    private fun MetadataContributorProfile.isEmptyShell(): Boolean =
        description.isNullOrBlank() && imageUrl.isNullOrBlank()

    /** Project a [ContributorPreviewLoadState.Failed] onto the still-current preview, ignoring a switched match/region. */
    private fun setPreviewFailed(
        target: PreviewTarget,
        message: String,
    ) {
        state.update { latest ->
            if (latest is ContributorMetadataUiState.Preview && latest.matches(target)) {
                latest.copy(loadState = ContributorPreviewLoadState.Failed(message))
            } else {
                latest
            }
        }
    }

    /**
     * Project [transform] onto the [ContributorPreviewLoadState.Ready] overlay, but only when the
     * still-current preview is still Ready for [target] — the same stale guard [setPreviewFailed]
     * uses, applied to the apply-mutation overlay. Returns whether the projection landed, so
     * [apply] can gate the one-shot [ContributorMetadataEvent.MetadataApplied] on it too: a
     * `clearSelection`/`changeRegion`/`selectCandidate` that ran while this apply was still in
     * flight must silently drop the outcome, not fire a stale success/error.
     */
    private fun updateReady(
        target: PreviewTarget,
        transform: (ContributorPreviewLoadState.Ready) -> ContributorPreviewLoadState.Ready,
    ): Boolean {
        var applied = false
        state.update { latest ->
            if (latest is ContributorMetadataUiState.Preview &&
                latest.matches(target) &&
                latest.loadState is ContributorPreviewLoadState.Ready
            ) {
                applied = true
                latest.copy(loadState = transform(latest.loadState))
            } else {
                latest
            }
        }
        return applied
    }
}
