package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.data.auth.invalidatesSession
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.ConnectionHealth
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.version.ClientIdentity
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Single source of truth for [ConnectionHealth], derived by precedence from three independent
 * signal slots: reachability (via [SyncEngineState]), auth liveness (via the injected
 * `authStateFlow`), and contract-compat evidence (peer version + behavioural parse failures).
 *
 * Precedence — [ConnectionHealth.Unreachable] > [ConnectionHealth.SessionExpired] >
 * [ConnectionHealth.Outdated] > [ConnectionHealth.Healthy] — mirrors the design's severity
 * ordering: a dead transport masks a dead session masks a version hint. Every signal resolves
 * independently as its own condition clears, so precedence never traps a state that should have
 * healed.
 *
 * Absorbs [ConnectionIssueReporter]'s auth-edge fold (see that class's KDoc) as
 * `report(AppError)` — same Authenticated-guard + once-per-lapse dedup discipline, generalized to a
 * `StateFlow<AuthState>` input instead of a concrete `AuthSession`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ConnectionHealthStore(
    engineState: SyncEngineState,
    private val authStateFlow: StateFlow<AuthState>,
    private val errorBus: ErrorBus,
    private val clientIdentity: ClientIdentity,
    private val localPreferences: LocalPreferences,
    scope: CoroutineScope,
) {
    // Best-effort dedup flag — same deliberately-unsynchronized discipline as
    // ConnectionIssueReporter: the worst race is one duplicate bus emission.
    private var reportedSinceAuthenticated = false

    private val lastProbeReachableAt = MutableStateFlow<Long?>(null)
    private val compatDetail = MutableStateFlow<String?>(null)

    private val connectionDown: Flow<Boolean> =
        engineState.observe().map { it.connection !is ConnectionState.Connected }.distinctUntilChanged()

    private val authDead: Flow<Boolean> =
        authStateFlow.map { it is AuthState.SessionLapsed }.distinctUntilChanged()

    // Reachability probes stay "fresh" evidence against the Unreachable signal for a bounded
    // window — a stale positive probe must not permanently mask a later real outage.
    private val probeFresh: Flow<Boolean> =
        lastProbeReachableAt
            .flatMapLatest { at ->
                if (at == null) {
                    flowOf(false)
                } else {
                    flow {
                        emit(true)
                        delay(PROBE_FRESHNESS_MS)
                        emit(false)
                    }
                }
            }.distinctUntilChanged()

    private val rawUnreachable: Flow<Boolean> =
        combine(connectionDown, probeFresh) { down, fresh -> down && !fresh }.distinctUntilChanged()

    // Debounced: a brief connectivity blip must not surface as Unreachable, but a sustained
    // outage does — and resolves the instant reachability returns (no debounce on the way out).
    private val unreachableSince: Flow<Long?> =
        rawUnreachable
            .flatMapLatest { unreachable ->
                if (unreachable) {
                    flow {
                        // Timestamp the instant the condition began, not the instant it surfaces —
                        // `sinceMillis` is documented as when the sustained-unreachable window opened.
                        val startedAt = currentEpochMilliseconds()
                        delay(UNREACHABLE_DEBOUNCE_MS)
                        emit(startedAt)
                    }
                } else {
                    flowOf<Long?>(null)
                }
            }.distinctUntilChanged()

    private val compat: Flow<ConnectionHealth.Outdated?> =
        combine(
            compatDetail,
            localPreferences.peerServerVersion,
            localPreferences.peerServerApi,
            localPreferences.outdatedDismissedFor,
        ) { detail, peerVersion, peerApi, dismissedFor ->
            val serverVersion =
                peerVersion ?: return@combine detail?.let { unknownOutdated() }
            val gap =
                evaluateVersionGap(clientIdentity, serverVersion, peerApi, behaviouralEvidence = detail != null)
                    ?: return@combine null
            if (dismissedFor == clientIdentity.version to serverVersion) null else gap
        }.distinctUntilChanged()

    /** The current derived connection health. Hot, replays the latest value. */
    val state: StateFlow<ConnectionHealth> =
        combine(unreachableSince, authDead, compat) { since, dead, outdated ->
            when {
                since != null -> ConnectionHealth.Unreachable(since)
                dead -> ConnectionHealth.SessionExpired
                outdated != null -> outdated
                else -> ConnectionHealth.Healthy
            }
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, ConnectionHealth.Healthy)

    init {
        // Re-arm the auth-report dedup on every return to Authenticated — mirrors
        // ConnectionIssueReporter's init, generalized to a StateFlow<AuthState>. A guarded
        // per-item try/catch keeps the collector alive across a transient failure instead of
        // dying (which would permanently break the dedup re-arm).
        scope.launch {
            authStateFlow.collect { auth ->
                try {
                    if (auth is AuthState.Authenticated) reportedSinceAuthenticated = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "authDedupReset collector failed" }
                }
            }
        }
    }

    /**
     * Report a typed failure observed by a headless seam. Cheap and non-suspending — safe to
     * call redundantly from every failure branch. Session-invalidating errors are forwarded to
     * the [ErrorBus] exactly once per lapse — but only while currently [AuthState.Authenticated],
     * so a failure reported before first login (or while already lapsed) never surfaces a
     * snackbar. Everything else is swallowed. Same guard + dedup discipline as the absorbed
     * `ConnectionIssueReporter`.
     */
    fun report(error: AppError) {
        if (authStateFlow.value !is AuthState.Authenticated) return
        if (!error.invalidatesSession()) return
        if (reportedSinceAuthenticated) return
        reportedSinceAuthenticated = true
        errorBus.emit(error)
    }

    /**
     * Report behavioural evidence of a contract mismatch (an unparseable/deprecated response
     * slice) observed by a headless seam. Never routes through auth — a parse failure is not a
     * session failure.
     */
    fun reportCompat(detail: String) {
        compatDetail.value = detail
    }

    /**
     * Report the outcome of a reachability probe. Only positive probes are recorded — a
     * reachable result is fresh evidence against [ConnectionHealth.Unreachable] for a bounded
     * window; there is nothing useful to record for a negative probe (absence of freshness is
     * already the default).
     */
    fun reportProbe(reachable: Boolean) {
        if (reachable) lastProbeReachableAt.value = currentEpochMilliseconds()
    }

    /** Persist the peer server's observed version + API contract version. */
    suspend fun updatePeerVersion(
        serverVersion: String,
        serverApi: String,
    ) {
        localPreferences.setPeerServerVersion(serverVersion, serverApi)
    }

    /**
     * Dismiss the current Outdated hint for the peer server's currently observed version. A
     * no-op if no peer version has been observed yet.
     */
    suspend fun dismissOutdated() {
        val serverVersion = localPreferences.peerServerVersion.value ?: return
        localPreferences.setOutdatedDismissedFor(clientIdentity.version to serverVersion)
    }

    private fun unknownOutdated() =
        ConnectionHealth.Outdated(clientVersion = clientIdentity.version, serverVersion = "?")

    companion object {
        /** How long a sustained-unreachable condition must persist before it surfaces. */
        const val UNREACHABLE_DEBOUNCE_MS = 3_000L

        /** How long a positive reachability probe counts as fresh evidence against Unreachable. */
        const val PROBE_FRESHNESS_MS = 90_000L
    }
}

/**
 * STUB for this PR: real semver-gap evaluation lands in a follow-up task. For now, only
 * behavioural evidence (a parse failure observed by a headless seam) produces a
 * [ConnectionHealth.Outdated] hint; a bare peer-version mismatch with no behavioural evidence is
 * not yet flagged.
 */
@Suppress("UnusedParameter")
private fun evaluateVersionGap(
    identity: ClientIdentity,
    serverVersion: String,
    serverApi: String?,
    behaviouralEvidence: Boolean,
): ConnectionHealth.Outdated? =
    if (behaviouralEvidence) ConnectionHealth.Outdated(identity.version, serverVersion) else null
