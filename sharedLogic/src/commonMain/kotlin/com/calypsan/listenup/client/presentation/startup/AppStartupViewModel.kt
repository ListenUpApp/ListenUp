package com.calypsan.listenup.client.presentation.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.remote.LibraryAdminRpcFactory
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ProfileRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
 * On every startup check the own profile is refreshed from the server in the
 * background via [ProfileRepository.refreshMyProfile] so local Room reflects the
 * latest server values (displayName, tagline, avatarType). The refresh is
 * fire-and-forget — failures are logged and never surfaced to the UI.
 */
class AppStartupViewModel(
    private val userRepository: UserRepository,
    private val libraryAdminRpcFactory: LibraryAdminRpcFactory,
    private val authSession: AuthSession,
    private val profileRepository: ProfileRepository,
    private val syncRepository: SyncRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AppStartupState())
    val state: StateFlow<AppStartupState> = _state.asStateFlow()

    /**
     * The single authoritative readiness state the navigation layer consumes via one `when`,
     * derived from the setup-check [state] and the sync layer's initial-population signal. This is
     * the consolidation of the previously-scattered overlay/gate booleans into one sealed type.
     *
     * Precedence: an unresolved or failed setup check, or a needs-setup answer, always outranks
     * population — `isServerScanning` is only meaningful once the library exists. Once the library
     * is populated and the client has imported it (`isServerScanning` false — see `applyScanEvent`),
     * the state is [LibraryReadiness.Ready].
     *
     * `Eagerly`, not `WhileSubscribed`, so [LibraryReadiness] is live for the whole ViewModel
     * lifetime — the splash gate and lifecycle hooks read state without an active UI subscription.
     */
    val readiness: StateFlow<LibraryReadiness> =
        combine(
            _state,
            syncRepository.isServerScanning,
            syncRepository.scanProgress,
        ) { s, scanning, progress ->
            when {
                !s.checkResolved || s.isChecking -> LibraryReadiness.Checking
                s.setupCheckFailed -> LibraryReadiness.CheckFailed
                s.needsLibrarySetup -> LibraryReadiness.NeedsSetup
                scanning -> LibraryReadiness.Populating(progress)
                else -> LibraryReadiness.Ready
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LibraryReadiness.Checking,
        )

    companion object {
        /** Apps backgrounded longer than this will re-run the library-setup check on resume. */
        const val BACKGROUND_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes
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
                        _state.value = AppStartupState(isChecking = true)
                        runLibrarySetupCheck()
                    } else {
                        logger.debug { "AppStartupViewModel: not authenticated — no library-setup check" }
                        _state.value = AppStartupState(isChecking = false)
                    }
                }
        }
    }

    // region Lifecycle hooks (call from MainActivity)

    /** Call from MainActivity.onPause to record when the app left the foreground. */
    fun onAppBackgrounded() {
        _state.value = _state.value.copy(backgroundedAtMs = currentEpochMilliseconds())
    }

    /**
     * Call from MainActivity.onResume to decide whether a re-check is needed.
     *
     * If the app was backgrounded for longer than BACKGROUND_THRESHOLD_MS the
     * library-setup state is reset and the check is re-run, showing the loading
     * screen again. Short resumes skip the check entirely.
     */
    fun onAppForegrounded() {
        val backgroundedAt = _state.value.backgroundedAtMs ?: return
        val elapsed = currentEpochMilliseconds() - backgroundedAt
        if (elapsed >= BACKGROUND_THRESHOLD_MS) {
            logger.info { "App was backgrounded for ${elapsed}ms (>= threshold) — re-checking library setup" }
            _state.value = AppStartupState(isChecking = true)
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
        _state.value =
            _state.value.copy(
                isChecking = false,
                needsLibrarySetup = false,
                setupCheckFailed = false,
                checkResolved = true,
            )
    }

    /** Re-run the library-setup check after a transient failure (the retry the nav layer offers). */
    fun retryLibrarySetupCheck() {
        _state.value = AppStartupState(isChecking = true)
        runLibrarySetupCheck()
    }

    private fun runLibrarySetupCheck() {
        viewModelScope.launch {
            try {
                val user = userRepository.refreshCurrentUser() ?: userRepository.getCurrentUser()
                logger.info { "AppStartupViewModel: resolved user=${user?.displayName}, isAdmin=${user?.isAdmin}" }

                // Refresh own profile in the background — keeps displayName/tagline/avatarType in sync.
                // Fire-and-forget: never blocks the startup check or surfaces failures to the UI.
                if (user != null) {
                    launch {
                        try {
                            profileRepository.refreshMyProfile()
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn(e) { "Own profile refresh failed at startup" }
                        }
                    }
                }

                if (user?.isAdmin == true) {
                    applyAdminSetupCheckResult(
                        libraryAdminRpcFactory.get().getSetupStatus(),
                    )
                } else {
                    logger.info {
                        "AppStartupViewModel: not an admin (user=${user?.displayName}, isAdmin=${user?.isAdmin}) — " +
                            "skipping library-setup check"
                    }
                    _state.value =
                        _state.value.copy(
                            isChecking = false,
                            needsLibrarySetup = false,
                            setupCheckFailed = false,
                            checkResolved = true,
                        )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "AppStartupViewModel: setup check failed unexpectedly" }
                resolveOfflineOrFail()
            }
        }
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
                _state.value =
                    _state.value.copy(
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
        val hasLocal = runCatching { syncRepository.hasLocalLibrary() }.getOrDefault(false)
        if (hasLocal) {
            logger.info { "library check failed but a local library exists — opening offline" }
            _state.value =
                _state.value.copy(
                    isChecking = false,
                    needsLibrarySetup = false,
                    setupCheckFailed = false,
                    checkResolved = true,
                )
        } else {
            logger.warn { "library check failed and no local library — surfacing retryable error" }
            _state.value =
                _state.value.copy(isChecking = false, setupCheckFailed = true, checkResolved = true)
        }
    }
}
