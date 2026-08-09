package com.calypsan.listenup.web

import com.calypsan.listenup.client.data.settings.seedServerUrlFromOrigin
import com.calypsan.listenup.client.di.jsSharedModules
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.web.features.bookdetail.graphBookDetail
import com.calypsan.listenup.web.nav.Router
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.renderComposable
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
            modules(jsSharedModules() + module { single<Worker> { createSqliteWorker() } })
        }.koin

    // The server URL must be seeded before the composition mounts, or a ViewModel's first RPC
    // call can race the seed write and dial an unconfigured client. Sequencing render as this
    // coroutine's continuation (rather than firing both concurrently) makes that race structurally
    // impossible without blocking the JS thread — suspension yields, it doesn't freeze the tab.
    CoroutineScope(Dispatchers.Default).launch {
        seedServerUrlIfNeeded(koin)

        val router = Router()
        renderComposable(root = mount) { WebAppRoot(router, openBookDetail = graphBookDetail(koin)) }
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
