package com.calypsan.listenup.web

import com.calypsan.listenup.web.nav.Router
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable

/**
 * Mounts the web body.
 *
 * The mount point is looked up rather than assumed, and a missing one is a no-op instead of a
 * crash. That is not defensiveness for its own sake: the browser TEST bundle imports this module
 * (for `createSqliteWorker`), which runs `main` on a page that has no app root. Throwing there
 * would fail the Kotest run for a reason that has nothing to do with the tests.
 */
fun main() {
    val mount = document.getElementById(MOUNT_ID) ?: return
    val router = Router()
    renderComposable(root = mount) { WebAppRoot(router) }
}

private const val MOUNT_ID = "app"
