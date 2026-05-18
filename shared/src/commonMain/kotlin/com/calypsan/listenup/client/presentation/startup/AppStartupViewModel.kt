package com.calypsan.listenup.client.presentation.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.remote.SetupApiContract
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * State for the app startup / authenticated-navigation initialisation check.
 *
 * @param isChecking True while the library-setup check is in progress.
 * @param needsLibrarySetup True if an admin user still needs to configure a library.
 * @param backgroundedAtMs Epoch-ms timestamp recorded when the app last went to background.
 *                         Null when the app has not yet been backgrounded this process.
 */
data class AppStartupState(
    val isChecking: Boolean = true,
    val needsLibrarySetup: Boolean = false,
    val backgroundedAtMs: Long? = null,
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
 */
class AppStartupViewModel(
    private val userRepository: UserRepository,
    private val setupApi: SetupApiContract,
) : ViewModel() {
    private val _state = MutableStateFlow(AppStartupState())
    val state: StateFlow<AppStartupState> = _state.asStateFlow()

    companion object {
        /** Apps backgrounded longer than this will re-run the library-setup check on resume. */
        const val BACKGROUND_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes
    }

    init {
        runLibrarySetupCheck()
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

    private fun runLibrarySetupCheck() {
        viewModelScope.launch {
            try {
                val user = userRepository.refreshCurrentUser() ?: userRepository.getCurrentUser()
                logger.debug { "AppStartupViewModel: user=${user?.displayName}, isAdmin=${user?.isAdmin}" }

                val needsSetup =
                    if (user?.isAdmin == true) {
                        when (val result = setupApi.getLibraryStatus()) {
                            is AppResult.Success -> {
                                logger.info { "AppStartupViewModel: library needsSetup=${result.data.needsSetup}" }
                                result.data.needsSetup
                            }

                            is AppResult.Failure -> {
                                logger.warn { "AppStartupViewModel: library status check failed, defaulting to false" }
                                false
                            }
                        }
                    } else {
                        false
                    }

                _state.value = _state.value.copy(isChecking = false, needsLibrarySetup = needsSetup)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "AppStartupViewModel: user check failed, proceeding to main app" }
                _state.value = _state.value.copy(isChecking = false, needsLibrarySetup = false)
            }
        }
    }
}
