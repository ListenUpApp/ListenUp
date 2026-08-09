package com.calypsan.listenup.web

import com.calypsan.listenup.client.di.jsSharedModules
import com.calypsan.listenup.web.features.bookdetail.graphBookDetail
import com.calypsan.listenup.web.nav.Router
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
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

    val router = Router()
    renderComposable(root = mount) { WebAppRoot(router, openBookDetail = graphBookDetail(koin)) }
}

private const val MOUNT_ID = "app"
