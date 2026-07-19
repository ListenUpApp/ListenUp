package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.data.auth.invalidatesSession
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.ConnectionHealth
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.version.ClientIdentity
import com.calypsan.listenup.client.domain.version.Semver
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
 * Absorbs the former `ConnectionIssueReporter`'s auth-edge fold as
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

    // Fires `true` once the firehose has been continuously down for [FIREHOSE_DOWN_PROBE_GRACE_MS],
    // resetting to `false` the instant it recovers. This bounds how long a positive UNAUTHENTICATED
    // probe may mask a dead firehose: such a probe proves the server is reachable, NOT that the SSE
    // stream is alive (an SSE-specific failure or an auth wedge keeps the firehose dead while the
    // 2s unauth probe keeps `probeFresh` pinned true → the banner stayed Healthy/Hidden forever, no
    // Retry offered). The grace preserves the probe's original no-flicker purpose for the *healthy*
    // reconnect flap; beyond it, a dead firehose surfaces as Unreachable regardless of the probe.
    private val firehoseDownBeyondGrace: Flow<Boolean> =
        connectionDown
            .flatMapLatest { down ->
                if (!down) {
                    flowOf(false)
                } else {
                    flow {
                        emit(false)
                        delay(FIREHOSE_DOWN_PROBE_GRACE_MS)
                        emit(true)
                    }
                }
            }.distinctUntilChanged()

    private val rawUnreachable: Flow<Boolean> =
        combine(connectionDown, probeFresh, firehoseDownBeyondGrace) { down, fresh, beyondGrace ->
            down && (!fresh || beyondGrace)
        }.distinctUntilChanged()

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
     * call redundantly from every failure branch. Dispatches on the error's shape, never both:
     * contract-mismatch evidence ([TransportError.ContractMismatch] / [TransportError.DataMalformed])
     * routes to [reportCompat] — non-blocking, independent of auth state, since a parse failure
     * can occur before first login and is never a session failure. Everything else falls through
     * to the auth path: session-invalidating errors are forwarded to the [ErrorBus] exactly once
     * per lapse — but only while currently [AuthState.Authenticated], so a failure reported before
     * first login (or while already lapsed) never surfaces a snackbar. Everything not
     * session-invalidating is swallowed. Same guard + dedup discipline as the absorbed
     * `ConnectionIssueReporter`.
     */
    fun report(error: AppError) {
        val compatEvidence =
            when (error) {
                is TransportError.ContractMismatch -> error.detail
                is TransportError.DataMalformed -> error.detail
                else -> null
            }
        if (compatEvidence != null) {
            reportCompat(compatEvidence)
            return
        }

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

        /**
         * How long the firehose may stay down while a positive (unauthenticated) probe suppresses
         * Unreachable, before the probe is overridden and Unreachable surfaces anyway. Shorter than
         * [PROBE_FRESHNESS_MS] — a probe proves the server is reachable, not that the firehose is
         * alive, so it may only mask a *transient* reconnect flap, not a sustained dead stream.
         */
        const val FIREHOSE_DOWN_PROBE_GRACE_MS = 15_000L
    }
}

/**
 * Evaluates whether the observed peer server represents a meaningful version gap worth
 * surfacing as [ConnectionHealth.Outdated]. Three independent signals, checked in order:
 * a declared API contract mismatch, behavioural evidence of an unparseable response, and a
 * major-version semver gap. Minor/patch skew alone is never a signal — it's expected drift
 * between compatible releases.
 */
private fun evaluateVersionGap(
    identity: ClientIdentity,
    serverVersion: String,
    serverApi: String?,
    behaviouralEvidence: Boolean,
): ConnectionHealth.Outdated? {
    // Rule 1: API contract mismatch — a declared, unambiguous incompatibility.
    if (serverApi != null && serverApi != identity.apiVersion) {
        return ConnectionHealth.Outdated(identity.version, serverVersion)
    }
    // Rule 2: behavioural evidence (a 2xx slice we couldn't parse — Phase 3 feeds this).
    if (behaviouralEvidence) {
        return ConnectionHealth.Outdated(identity.version, serverVersion)
    }
    // Rule 3: a major-version gap. Minor/patch skew is NOT a signal (rule 4 → null).
    val client = Semver.parseOrNull(identity.version) ?: return null
    val server = Semver.parseOrNull(serverVersion) ?: return null
    return if (client.major != server.major) {
        ConnectionHealth.Outdated(identity.version, serverVersion)
    } else {
        null
    }
}
