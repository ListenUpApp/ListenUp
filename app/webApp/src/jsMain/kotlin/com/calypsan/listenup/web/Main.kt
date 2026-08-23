package com.calypsan.listenup.web

import com.calypsan.listenup.client.data.settings.seedServerUrlFromOrigin
import com.calypsan.listenup.client.di.jsSharedModules
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.web.di.webPlaybackModule
import com.calypsan.listenup.web.features.auth.AuthGate
import com.calypsan.listenup.web.features.auth.graphAuth
import com.calypsan.listenup.web.features.bookdetail.graphBookDetail
import com.calypsan.listenup.web.features.bookedit.graphBookEdit
import com.calypsan.listenup.web.features.contributordetail.graphContributorDetail
import com.calypsan.listenup.web.features.contributors.graphContributors
import com.calypsan.listenup.web.features.library.graphLibrary
import com.calypsan.listenup.web.features.nowplaying.graphPlayback
import com.calypsan.listenup.web.features.search.graphSearch
import com.calypsan.listenup.web.motion.captureHeroOriginBeforeRouteChange
import com.calypsan.listenup.web.nav.Router
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.renderComposable
import com.calypsan.listenup.client.domain.repository.UserRepository
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.w3c.dom.Worker

/**
 * Mounts the web body over the real client graph.
 *
 * The mount point is looked up rather than assumed, and a missing one is a no-op instead of a
 * crash. That is not defensiveness for its own sake: the browser TEST bundle imports this module
 * (for `createSqliteWorker`), which runs `main` on a page that has no app root. Throwing there
 * would fail the Kotest run for a reason that has nothing to do with the tests — and everything
 * below the guard (starting Koin, spawning the SQLite worker) must not happen on that page
 * either, since the specs boot their own isolated graphs.
 */
fun main() {
    val mount = document.getElementById(MOUNT_ID) ?: return

    // The worker is the one thing :app:sharedLogic cannot supply — it ships no worker script —
    // so it is the browser application's contribution to an otherwise shared graph.
    val koin =
        startKoin {
            modules(jsSharedModules() + webPlaybackModule + module { single<Worker> { createSqliteWorker() } })
        }.koin

    // The server URL must be seeded before the composition mounts, or a ViewModel's first RPC
    // call can race the seed write and dial an unconfigured client. Sequencing render as this
    // coroutine's continuation (rather than firing both concurrently) makes that race structurally
    // impossible without blocking the JS thread — suspension yields, it doesn't freeze the tab.
    CoroutineScope(Dispatchers.Default).launch {
        seedServerUrlIfNeeded(koin)
        connectSyncWhenAuthenticated(koin, this)

        val router = Router(beforeRouteChange = ::captureHeroOriginBeforeRouteChange)
        renderComposable(root = mount) {
            AuthGate(
                authGraph = graphAuth(koin),
                router = router,
                openBookDetail = graphBookDetail(koin),
                openBookEdit = graphBookEdit(koin),
                openContributorDetail = graphContributorDetail(koin),
                openContributors = graphContributors(koin),
                openLibrary = graphLibrary(koin),
                openSearch = graphSearch(koin),
                openPlayback = graphPlayback(koin),
                observeIsAdmin = { koin.get<UserRepository>().observeIsAdmin() },
            )
        }
    }
}

/**
 * Connects realtime sync on every transition into [AuthState.Authenticated].
 *
 * The analogue of `MainActivity`'s `repeatOnLifecycle` collector, and the reason a signed-in browser
 * had an empty library: `clientSyncModule` wires the whole sync engine into the browser graph and
 * nothing ever started it.
 *
 * Collected on the app-level scope rather than inside a composable — a connection whose lifetime is
 * tied to composition would be torn down by a route change. `connectRealtime()` is single-flight, so
 * a re-entry from a later transition is safe.
 */
private fun connectSyncWhenAuthenticated(
    koin: Koin,
    scope: CoroutineScope,
) {
    val authSession = koin.get<AuthSession>()
    val syncRepository = koin.get<SyncRepository>()
    scope.launch {
        authSession.authState
            .map { it is AuthState.Authenticated }
            .distinctUntilChanged()
            .collect { authenticated ->
                if (!authenticated) return@collect
                try {
                    syncRepository.connectRealtime()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never take the tab down over a sync failure: the shell and every already
                    // synced row still work, which is the whole point of reading from Room.
                    console.warn("Failed to connect realtime sync: ${e.message}")
                }
            }
    }
}

/**
 * Seeds [ServerConfig] from this page's origin the first time the browser boots with nothing
 * stored. Never overwrites a URL a prior session (or a manual override) already set — see
 * [seedServerUrlFromOrigin]. A seeding failure is logged and swallowed rather than propagated:
 * failing to boot the whole app over a URL write is a worse outcome than leaving the user to
 * configure it manually, and "Never Stranded" means the fallback has to actually be reachable.
 */
private suspend fun seedServerUrlIfNeeded(koin: Koin) {
    // Resolution is INSIDE the try, not at the call site. Rendering is this coroutine's
    // continuation, so anything that escapes here takes the whole UI down with it — and a
    // white page with an unhandled coroutine exception in the console is the worst way to
    // report "the server URL could not be seeded", a condition the user can fix by hand.
    try {
        val serverConfig = koin.get<ServerConfig>()
        if (serverConfig.hasServerConfigured()) return
        serverConfig.setServerUrl(ServerUrl(seedServerUrlFromOrigin(stored = null, origin = window.location.origin)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        console.warn("Failed to seed server URL from page origin: ${e.message}")
    }
}

private const val MOUNT_ID = "app"
