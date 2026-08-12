package com.calypsan.listenup.client.diagnostics

import com.calypsan.listenup.client.data.settings.seedServerUrlFromOrigin
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.presentation.auth.LoginViewModel
import com.calypsan.listenup.client.presentation.auth.SetupViewModel
import com.calypsan.listenup.core.ServerUrl
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.dom.Worker
import kotlin.time.Duration.Companion.seconds

/**
 * What an end-to-end sync self-check observed. Plain values, for the same reason [AuthArcProbe]
 * uses them: the Koin graph, Room and the RPC proxies all stay `internal` to this module.
 */
data class LibrarySyncProbe(
    /** True once `authState` reached [AuthState.Authenticated], however it got there. */
    val reachedAuthenticated: Boolean,
    /** True once `connectRealtime()` returned without throwing. */
    val connectSucceeded: Boolean,
    /** Books in the browser's own Room store when the wait ended; -1 if never authenticated. */
    val localBookCount: Int,
    /** Message from whatever failed, or null when the arc completed. */
    val failure: String?,
)

/**
 * Drives auth, then sync, in a browser against a real server, and reports how many books landed in
 * the browser's OWN store.
 *
 * The book count is the point. Reaching [AuthState.Authenticated] and calling `connectRealtime()`
 * prove a trigger fired; neither says whether a single row reached Room. A check that stops at the
 * trigger is structurally blind to the failure this arc is most likely to have — an engine that
 * starts and then dies against SQLite-WASM.
 *
 * **Order-independent by construction.** [AuthArcProbe] may or may not have already created the
 * first admin, and both specs compile into one bundle with no ordering guarantee. So this signs in
 * when the server already has users and sets up when it does not, rather than assuming either.
 *
 * Waits rather than hangs, and never throws: a count that never arrives is reported as what it was,
 * so a failure reads as a failed assertion instead of a truncated suite.
 *
 * [dbName] overrides the production database name so OPFS state from other runs cannot leak in.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun probeLibrarySync(
    worker: Worker,
    dbName: String,
    email: String,
    password: String,
): LibrarySyncProbe {
    val app = browserGraph(worker, dbName)

    return try {
        // Seed the server URL before anything touches the network — an isolated graph inherits
        // nothing from the running app, and an unseeded ServerConfig fails every call as
        // "network unavailable" on a machine whose network is fine. Same reasoning as probeAuthArc.
        val serverConfig = app.koin.get<ServerConfig>()
        if (!serverConfig.hasServerConfigured()) {
            serverConfig.setServerUrl(
                ServerUrl(seedServerUrlFromOrigin(stored = null, origin = window.location.origin)),
            )
        }

        val authSession = app.koin.get<AuthSession>()
        authSession.initializeAuthState()

        // Set up only when the server is genuinely empty; otherwise sign in. Which branch runs
        // depends on whether AuthArcTest got here first, and neither spec may depend on that.
        if (authSession.authState.value is AuthState.NeedsSetup) {
            app.koin.get<SetupViewModel>().onSetupSubmit(
                firstName = PROBE_FIRST_NAME,
                lastName = PROBE_LAST_NAME,
                email = email,
                password = password,
                passwordConfirm = password,
            )
        } else {
            app.koin.get<LoginViewModel>().onLoginSubmit(email = email, password = password)
        }

        val reachedAuthenticated =
            withTimeoutOrNull(AUTH_TIMEOUT) {
                authSession.authState.filterIsInstance<AuthState.Authenticated>().first()
            } != null

        if (!reachedAuthenticated) {
            return LibrarySyncProbe(false, false, NO_BOOK_COUNT, "never reached Authenticated")
        }

        var failure: String? = null
        val connectSucceeded =
            try {
                app.koin.get<SyncRepository>().connectRealtime()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = "connectRealtime failed: ${e.message}"
                false
            }

        // The local store, never a network fetch: a fetch would pass with Room completely empty,
        // which is the exact failure this probe exists to catch.
        val books =
            withTimeoutOrNull(SYNC_TIMEOUT) {
                app.koin
                    .get<BookRepository>()
                    .observeBookListItems()
                    .first { it.isNotEmpty() }
            }

        LibrarySyncProbe(
            reachedAuthenticated = true,
            connectSucceeded = connectSucceeded,
            localBookCount = books?.size ?: 0,
            failure = failure ?: if (books == null) "no books reached Room before the timeout" else null,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LibrarySyncProbe(false, false, NO_BOOK_COUNT, "probe threw: ${e.message}")
    } finally {
        app.close()
    }
}

private val AUTH_TIMEOUT = 20.seconds

/** Longer than the auth wait: a first sync seeds a whole library, not a single round trip. */
private val SYNC_TIMEOUT = 60.seconds

private const val NO_BOOK_COUNT = -1
private const val PROBE_FIRST_NAME = "Probe"
private const val PROBE_LAST_NAME = "Admin"
