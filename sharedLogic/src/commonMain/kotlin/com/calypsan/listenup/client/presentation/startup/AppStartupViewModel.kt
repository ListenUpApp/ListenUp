package com.calypsan.listenup.client.presentation.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrDefault
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.LibraryAdminRpcFactory
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val logger = KotlinLogging.logger {}

/**
 * State for the app startup / authenticated-navigation initialisation check.
 *
 * @param isChecking True while the library-setup check is in progress.
 * @param needsLibrarySetup True if an admin user still needs to configure a library.
 * @param setupCheckFailed True if the admin's library-setup check could not be completed
 *                         (network failure or unexpected error). The nav layer surfaces a
 *                         retryable error instead of silently dropping the admin into an
 *                         empty Shell ("honest over silent").
 * @param backgroundedAtMs Epoch-ms timestamp recorded when the app last went to background.
 *                         Null when the app has not yet been backgrounded this process.
 */
data class AppStartupState(
    val isChecking: Boolean = true,
    val needsLibrarySetup: Boolean = false,
    val setupCheckFailed: Boolean = false,
    val backgroundedAtMs: Long? = null,
    /**
     * True once a setup check has produced a definitive result this authenticated session. Lets the
     * nav layer keep the readiness overlay up between "authenticated" and the first check result —
     * otherwise the default `Shell` back-stack entry flashes before the check resolves, because a
     * not-yet-checked state (`isChecking=false, needsLibrarySetup=false`) is indistinguishable from
     * a resolved "go to shell" one.
     */
    val checkResolved: Boolean = false,
    /**
     * True once the user taps "Continue" on a stalled "Building your library" screen. Latches the
     * readiness gate to [LibraryReadiness.Ready] so the shell mounts on the partial library that
     * incremental sync has already landed in Room — the never-stranded escape from a server scan
     * that can't complete. Process-lived, mirroring the sync layer's initial-scan latch: a fresh
     * launch or a re-auth starts `false`, and once latched the populating gate never re-shows this
     * session.
     */
    val populatingDismissed: Boolean = false,
)

/**
 * ViewModel that guards the post-login initialisation flow.
 *
 * Lives in the Activity's ViewModelStore, so it survives configuration changes
 * (rotation, split-screen, etc.) without re-running the library-setup network call.
 *
 * Lifecycle rules:
 * - Cold start / process death: isChecking starts true; the check runs once.
 * - Config change (rotation, etc.): ViewModel is reused; isChecking is already
 *   false after the first check, so no loading screen is shown.
 * - Short background resume (< BACKGROUND_THRESHOLD_MS): same as config change.
 * - Long background (>= BACKGROUND_THRESHOLD_MS): onAppForegrounded resets the
 *   state and re-runs the check so stale library-setup state is refreshed.
 *
 * The current user's identity is refreshed from the server via
 * [UserRepository.refreshCurrentUser]; profile facets (displayName, tagline, avatar)
 * arrive through the `public_profiles` sync stream, so no separate profile refresh runs here.
 */
class AppStartupViewModel(
    private val userRepository: UserRepository,
    private val libraryAdminRpcFactory: LibraryAdminRpcFactory,
    private val authSession: AuthSession,
    private val syncRepository: SyncRepository,
) : ViewModel() {
    val state: StateFlow<AppStartupState>
        field = MutableStateFlow(AppStartupState())

    /**
     * The single authoritative readiness state the navigation layer consumes via one `when`,
     * derived from the setup-check [state] and the sync layer's initial-population signal. This is
     * the consolidation of the previously-scattered overlay/gate booleans into one sealed type.
     *
     * Precedence: an unresolved or failed setup check, or a needs-setup answer, always outranks
     * population — the population signal is only meaningful once the library exists. Population is
     * driven by the server-authoritative [SyncRepository.isBuildingInitialLibrary] — true only while a
     * scan is actively building or the library is still empty and unstamped, false once the server
     * records `initial_scan_completed_at` (synced into Room) or any book lands. So a rescan of a
     * populated library, and a fresh device joining an existing library, both resolve straight to
     * [LibraryReadiness.Ready] — no per-process latch.
     *
     * `Eagerly`, not `WhileSubscribed`, so [LibraryReadiness] is live for the whole ViewModel
     * lifetime — the splash gate and lifecycle hooks read state without an active UI subscription.
     * The Eagerly collector also keeps the populating stall watchdog ([flatMapLatest] below) ticking
     * regardless of UI subscription, so a stall is detected even between recompositions.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val readiness: StateFlow<LibraryReadiness> =
        combine(
            state,
            syncRepository.isBuildingInitialLibrary,
            syncRepository.scanProgress,
        ) { s, scanning, progress ->
            when {
                !s.checkResolved || s.isChecking -> LibraryReadiness.Checking

                s.setupCheckFailed -> LibraryReadiness.CheckFailed

                s.needsLibrarySetup -> LibraryReadiness.NeedsSetup

                // Once the user has escaped a stalled populate, the latch wins over a still-true
                // scan signal so the shell stays mounted on the partial library.
                scanning && !s.populatingDismissed -> LibraryReadiness.Populating(progress)

                else -> LibraryReadiness.Ready
            }
        }.flatMapLatest { readiness ->
            // Stall watchdog, only for the populating gate. Emit the live state immediately, then —
            // if no fresh upstream value (advancing progress, a cleared scan, a dismiss) arrives
            // within POPULATING_STALL_TIMEOUT_MS — re-emit it as `stalled`. flatMapLatest cancels and
            // restarts this inner flow on every upstream change, so each progress tick resets the
            // timer: a healthy slow scan keeps advancing and never reaches the stalled emission;
            // only a quiet, stuck scan does.
            if (readiness is LibraryReadiness.Populating) {
                flow {
                    emit(readiness)
                    delay(POPULATING_STALL_TIMEOUT_MS)
                    emit(readiness.copy(stalled = true))
                }
            } else {
                flowOf(readiness)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LibraryReadiness.Checking,
        )

    companion object {
        /** Apps backgrounded longer than this will re-run the library-setup check on resume. */
        const val BACKGROUND_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes

        /**
         * Overall bound on the startup library-setup check. Its RPC calls ride the `/api/rpc/authed`
         * WebSocket, and Ktor's `HttpTimeout` does not catch a post-upgrade RPC stall on
         * Darwin/URLSession (documented on `InstanceRpcFactory`) — so an unreachable server would
         * otherwise hang the check forever, leaving `readiness` on `Checking` and the app stranded on
         * the splash screen. On timeout the check falls through to the offline resolution. 12s mirrors
         * the `InstanceRpcFactory` server-probe bound for the same transport.
         */
        const val SETUP_CHECK_TIMEOUT_MS = 12_000L

        /**
         * How long the "Building your library" gate may go without scan progress advancing before it
         * is marked stalled and offers the never-stranded "Continue" escape. A healthy scan advances
         * progress far more often than this; only a stuck or crashed server scan reaches it.
         */
        const val POPULATING_STALL_TIMEOUT_MS = 45_000L
    }

    init {
        // The library-setup check must run when the user is AUTHENTICATED — not at
        // construction. This VM is created eagerly at app launch (MainActivity holds it
        // for the foreground/background hooks and the splash gate) and is Activity-scoped,
        // so a one-shot init check would run before login, resolve a null user, cache
        // "no setup needed", and never re-run once the user actually registers/logs in.
        // Observing authState re-runs the check on each transition to Authenticated.
        observeAuthState()
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authSession.authState
                .map { it is AuthState.Authenticated }
                .distinctUntilChanged()
                .collect { authenticated ->
                    if (authenticated) {
                        logger.info { "AppStartupViewModel: authenticated — running library-setup check" }
                        state.value = AppStartupState(isChecking = true)
                        runLibrarySetupCheck()
                    } else {
                        logger.debug { "AppStartupViewModel: not authenticated — no library-setup check" }
                        state.value = AppStartupState(isChecking = false)
                    }
                }
        }
    }

    // region Lifecycle hooks (call from MainActivity)

    /** Call from MainActivity.onPause to record when the app left the foreground. */
    fun onAppBackgrounded() {
        state.value = state.value.copy(backgroundedAtMs = currentEpochMilliseconds())
    }

    /**
     * Call from MainActivity.onResume to decide whether a re-check is needed.
     *
     * If the app was backgrounded for longer than BACKGROUND_THRESHOLD_MS the
     * library-setup state is reset and the check is re-run, showing the loading
     * screen again. Short resumes skip the check entirely.
     */
    fun onAppForegrounded() {
        val backgroundedAt = state.value.backgroundedAtMs ?: return
        val elapsed = currentEpochMilliseconds() - backgroundedAt
        if (elapsed >= BACKGROUND_THRESHOLD_MS) {
            logger.info { "App was backgrounded for ${elapsed}ms (>= threshold) — re-checking library setup" }
            state.value = AppStartupState(isChecking = true)
            runLibrarySetupCheck()
        } else {
            logger.debug { "App resumed after ${elapsed}ms — skipping library setup re-check" }
        }
    }

    // endregion

    /**
     * Clear the needs-setup state once the user finishes the create-library wizard. Without this the
     * stale `needsLibrarySetup = true` keeps the nav layer's pre-wizard readiness overlay latched on
     * top of the shell forever after setup. Call from the setup-complete callback.
     */
    fun onLibrarySetupComplete() {
        state.value =
            state.value.copy(
                isChecking = false,
                needsLibrarySetup = false,
                setupCheckFailed = false,
                checkResolved = true,
            )
    }

    /**
     * Escape the "Building your library" gate when the initial scan has stalled (see
     * [LibraryReadiness.Populating.stalled]). Latches [AppStartupState.populatingDismissed] so
     * [readiness] computes [LibraryReadiness.Ready] and the shell mounts on the partial library
     * already in Room — books still scanning keep appearing as incremental sync lands them, and the
     * user can re-run the scan from Settings whenever they like. Never re-shows the gate this session.
     */
    fun onContinueToPartialLibrary() {
        state.value = state.value.copy(populatingDismissed = true)
    }

    /** Re-run the library-setup check after a transient failure (the retry the nav layer offers). */
    fun retryLibrarySetupCheck() {
        state.value = AppStartupState(isChecking = true)
        runLibrarySetupCheck()
    }

    private fun runLibrarySetupCheck() {
        viewModelScope.launch {
            try {
                // Offline-first: if the library is already in Room, the app is Ready the instant we can
                // read local state — never block the splash on the network when there is content to show.
                // Server work (user refresh, setup-status, and the delta sync driven separately by
                // connectRealtime) is background reconciliation that can only upgrade the experience.
                if (syncRepository.hasLocalLibrary()) {
                    markReady()
                    launchBackgroundUserRefresh()
                    return@launch
                }

                // No local library. A non-admin has nothing to set up, so go straight to the (empty)
                // shell that incremental sync fills — still no network on the critical path. isAdmin is
                // read from the locally-cached user; only a not-yet-cached user needs the server for it.
                val localUser = userRepository.getCurrentUser()
                if (localUser != null && !localUser.isAdmin) {
                    markReady()
                    launchBackgroundUserRefresh()
                    return@launch
                }

                // Genuine cold start: no local library and a (possibly) admin user. This is the only case
                // that must consult the server to decide wizard-vs-shell — and the only case where there
                // is nothing local to show anyway. Bound it so an unreachable server can't strand the
                // splash (HttpTimeout doesn't catch a post-upgrade RPC stall on Darwin). withTimeoutOrNull
                // — not withTimeout — so a timeout returns null and falls through to the offline
                // resolution rather than throwing a TimeoutCancellationException that the guard re-raises.
                val completed =
                    withTimeoutOrNull(SETUP_CHECK_TIMEOUT_MS) {
                        val user = userRepository.refreshCurrentUser() ?: localUser
                        logger.info { "AppStartupViewModel: resolved user=${user?.displayName}, isAdmin=${user?.isAdmin}" }

                        if (user?.isAdmin == true) {
                            applyAdminSetupCheckResult(
                                libraryAdminRpcFactory.get().getSetupStatus(),
                            )
                        } else {
                            logger.info {
                                "AppStartupViewModel: not an admin (user=${user?.displayName}, isAdmin=${user?.isAdmin}) — " +
                                    "skipping library-setup check"
                            }
                            markReady()
                        }
                    }

                if (completed == null) {
                    logger.warn {
                        "AppStartupViewModel: setup check did not complete within ${SETUP_CHECK_TIMEOUT_MS}ms " +
                            "(server unreachable) — resolving offline"
                    }
                    resolveOfflineOrFail()
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "AppStartupViewModel: setup check failed unexpectedly" }
                resolveOfflineOrFail()
            }
        }
    }

    /** Resolve the gate to Ready — library present or nothing to set up — so the shell mounts now. */
    private fun markReady() {
        state.value =
            state.value.copy(
                isChecking = false,
                needsLibrarySetup = false,
                setupCheckFailed = false,
                checkResolved = true,
            )
    }

    /**
     * Best-effort background refresh of the current user after a local-first [markReady]. Keeps isAdmin
     * / profile current without gating the splash: [UserRepository.refreshCurrentUser] already swallows
     * transport failures to null (offline is fine) and re-raises CancellationException. It never touches
     * [readiness] — a user with local content is already Ready.
     */
    private fun launchBackgroundUserRefresh() {
        viewModelScope.launch { userRepository.refreshCurrentUser() }
    }

    /**
     * Applies the result of [LibraryAdminService.getSetupStatus] to [_state].
     *
     * On success, records whether the library needs setup. On failure, falls back to the
     * offline-first policy via [resolveOfflineOrFail]: a returning admin with a cached
     * local library opens offline; a genuinely fresh admin with no library sees the
     * retryable-error wall.
     */
    private suspend fun applyAdminSetupCheckResult(result: AppResult<SetupStatus>) {
        when (result) {
            is AppResult.Success -> {
                logger.info { "AppStartupViewModel: library needsSetup=${result.data.needsSetup}" }
                state.value =
                    state.value.copy(
                        isChecking = false,
                        needsLibrarySetup = result.data.needsSetup,
                        setupCheckFailed = false,
                        checkResolved = true,
                    )
            }

            is AppResult.Failure -> {
                logger.warn { "AppStartupViewModel: library status check failed: ${result.error.code}" }
                resolveOfflineOrFail()
            }
        }
    }

    /**
     * Offline-first fallback: when the admin setup check cannot reach the server, open offline
     * if a local library exists. Only surface [AppStartupState.setupCheckFailed] when there is
     * genuinely no cached library to fall back to (fresh admin, first startup offline).
     */
    private suspend fun resolveOfflineOrFail() {
        // suspendRunCatching (unlike stdlib runCatching) re-throws CancellationException, so a
        // cancelled probe propagates instead of being mistaken for "no local library".
        val hasLocal = suspendRunCatching { syncRepository.hasLocalLibrary() }.getOrDefault { false }
        if (hasLocal) {
            logger.info { "library check failed but a local library exists — opening offline" }
            markReady()
        } else {
            logger.warn { "library check failed and no local library — surfacing retryable error" }
            state.value =
                state.value.copy(isChecking = false, setupCheckFailed = true, checkResolved = true)
        }
    }
}
