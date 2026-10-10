package com.calypsan.listenup.client.diagnostics

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.refreshAuthTokens
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.AuthRepositoryImpl
import com.calypsan.listenup.client.data.repository.RECENT_REFRESH_WINDOW
import com.calypsan.listenup.client.data.settings.seedServerUrlFromOrigin
import com.calypsan.listenup.client.di.browserSecureStorage
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.presentation.auth.LoginViewModel
import com.calypsan.listenup.client.presentation.auth.SetupViewModel
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.core.ServerUrl
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.w3c.dom.Worker
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * What two browser tabs sharing one `localStorage` observed across interleaved token refreshes.
 * Plain values, same reasoning as [AuthArcProbe].
 */
data class TwoTabSessionProbe(
    /** Tab A signed in, and tab B started up from what A stored and used the session once. */
    val bothSignedIn: Boolean,
    /**
     * One entry per refresh, in order A, B, A, B: `ok`, `lapsed` when the refresh was refused and
     * the session was lapsed, or the failure code of any other fault.
     */
    val refreshOutcomes: List<String>,
    /** Simple class name of tab A's [AuthState] when it last acted, or null if it never got there. */
    val finalStateOfA: String?,
    /** Simple class name of tab B's [AuthState] when it last acted, or null if it never got there. */
    val finalStateOfB: String?,
    /** An authenticated RPC call from tab A, started afresh at the end, succeeded. */
    val authedCallOnASucceeded: Boolean,
    /** An authenticated RPC call from tab B, started afresh at the end, succeeded. */
    val authedCallOnBSucceeded: Boolean,
    /** Message from whatever threw, or null when the arc completed. */
    val failure: String?,
)

/**
 * Simulates two tabs on one origin and interleaves their token refreshes: A, B, A, B.
 *
 * Koin shares a module's singletons between every application that loads it, so two graphs cannot
 * be alive at once. A tab is instead what makes tabs differ from each other: its own credential
 * storage, built by the same [browserSecureStorage] the production graph binds, so whatever that
 * storage caches in memory it caches here, per tab. Each step starts a fresh graph over the tab's
 * storage, acts, and closes it; the `localStorage` underneath is the one shared by both tabs.
 *
 * The server rotates a refresh token on every use, so the tabs only stay signed in together if
 * each presents the token the other last stored rather than a copy it read earlier.
 *
 * Signs in when the server already has users and sets up when it does not, because
 * [probeAuthArc] may or may not have run first. Never throws: a stage that fails is reported as
 * what it was, so a failure reads as a failed assertion instead of a truncated suite.
 */
@Suppress("TooGenericExceptionCaught", "LongMethod")
suspend fun probeTwoTabRefresh(
    workerA: Worker,
    workerB: Worker,
    dbNameA: String,
    dbNameB: String,
    email: String,
    password: String,
): TwoTabSessionProbe {
    val tabA = SimulatedTab(workerA, dbNameA)
    val tabB = SimulatedTab(workerB, dbNameB)
    val outcomes = mutableListOf<String>()
    var stage = "sign-in on A"
    var stateA: String? = null
    var stateB: String? = null
    return try {
        val signedInA = tabA.act { signIn(email, password) }

        stage = "start of B"
        val warmedUpB =
            tabB.act {
                koin.get<AuthSession>().authState.value is AuthState.Authenticated &&
                    koin.get<AdminRepository>().getUsers() is AppResult.Success
            }

        stage = "refreshes"
        stateA = tabA.act { refreshAndNote(outcomes) }
        stateB = tabB.act { refreshAndNote(outcomes) }
        stateA = tabA.act { refreshAndNote(outcomes) }
        stateB = tabB.act { refreshAndNote(outcomes) }

        stage = "authed calls"
        TwoTabSessionProbe(
            bothSignedIn = signedInA && warmedUpB,
            refreshOutcomes = outcomes,
            finalStateOfA = stateA,
            finalStateOfB = stateB,
            authedCallOnASucceeded = tabA.act { koin.get<AdminRepository>().getUsers() is AppResult.Success },
            authedCallOnBSucceeded = tabB.act { koin.get<AdminRepository>().getUsers() is AppResult.Success },
            failure = null,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TwoTabSessionProbe(false, outcomes, stateA, stateB, false, false, "threw during $stage: ${e.message}")
    }
}

/** A tab's own credential storage and clock; everything else is rebuilt for each step it takes. */
private class SimulatedTab(
    private val worker: Worker,
    private val dbName: String,
) {
    private val storage: SecureStorage = browserSecureStorage()

    /**
     * The tab's clock. Moved past the repository's recent-refresh window before every step, because
     * real refreshes are minutes apart and one inside the window is answered without asking the
     * server.
     */
    private val clock = TestTimeSource()

    suspend fun <T> act(step: suspend KoinApplication.() -> T): T {
        clock += RECENT_REFRESH_WINDOW + 1.seconds
        val app = browserGraph(worker, dbName, listOf(bindings()))
        return try {
            app.koin.get<ServerConfig>().seedFromOrigin()
            app.koin.get<AuthSession>().initializeAuthState()
            app.step()
        } finally {
            app.close()
        }
    }

    private fun bindings(): Module =
        module {
            single<SecureStorage> { storage }
            single<AuthRepository> {
                AuthRepositoryImpl(
                    authPublicChannel = rpcChannel(),
                    authedChannel = rpcChannel(),
                    authSession = get(),
                    scope = get(qualifier = named(APP_SCOPE)),
                    clientVersion = get(qualifier = named("clientVersion")),
                    timeSource = clock,
                )
            }
        }
}

/**
 * Refreshes the way the bearer plugin does on a 401, so a refused token lapses the session exactly
 * as it would in a page, and returns the resulting [AuthState]'s simple name.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun KoinApplication.refreshAndNote(outcomes: MutableList<String>): String? {
    val session = koin.get<AuthSession>()
    val repository = koin.get<AuthRepository>()
    outcomes +=
        try {
            if (refreshAuthTokens(session, repository::refreshAccessToken) != null) "ok" else "lapsed"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "threw ${e::class.simpleName}"
        }
    return session.authState.value::class.simpleName
}

/** Signs in on this graph, or sets the server up when it has no users yet. */
private suspend fun KoinApplication.signIn(
    email: String,
    password: String,
): Boolean {
    val session = koin.get<AuthSession>()
    // A session left in storage by an earlier spec would satisfy the wait below before the sign-in ran.
    if (session.authState.value is AuthState.Authenticated) session.clearAuthTokens()
    if (session.authState.value is AuthState.NeedsSetup) {
        koin.get<SetupViewModel>().onSetupSubmit(
            firstName = PROBE_FIRST_NAME,
            lastName = PROBE_LAST_NAME,
            email = email,
            password = password,
            passwordConfirm = password,
        )
    } else {
        koin.get<LoginViewModel>().onLoginSubmit(email = email, password = password)
    }
    return withTimeoutOrNull(
        TWO_TAB_TIMEOUT,
    ) { session.authState.filterIsInstance<AuthState.Authenticated>().first() } !=
        null
}

private suspend fun ServerConfig.seedFromOrigin() {
    if (!hasServerConfigured()) {
        setServerUrl(ServerUrl(seedServerUrlFromOrigin(stored = null, origin = window.location.origin)))
    }
}

private val TWO_TAB_TIMEOUT = 20.seconds

private const val APP_SCOPE = "appScope"
private const val PROBE_FIRST_NAME = "Probe"
private const val PROBE_LAST_NAME = "Admin"
