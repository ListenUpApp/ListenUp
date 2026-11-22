package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowUiState
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.nav.LocalRouter
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.discardDialogButton
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/** The text of the open dialog, or empty when none is open. */
private fun dialogText(host: HTMLElement): String = host.querySelector("dialog")?.textContent.orEmpty()

/**
 * A running transfer is work the reader would lose: leaving the upload or the Audiobookshelf import
 * while it runs asks first, through the same guard every edit form uses, and stopping goes through
 * the session before the page leaves. Idle and finished pages leave at once.
 */
class TransferGuardTest :
    FunSpec({
        val mounts = MountRegistry()
        var originalUrl = ""
        beforeTest { originalUrl = window.location.pathname + window.location.search }
        afterTest {
            mounts.disposeAll()
            window.history.replaceState(null, "", originalUrl)
        }

        fun mountUpload(
            router: Router,
            state: UploadBooksUiState,
            onCancel: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                CompositionLocalProvider(LocalRouter provides router) {
                    UploadRoute(router = router, openUpload = fixedUpload(state, onCancel = onCancel), onToast = {})
                }
            }

        fun mountImport(
            router: Router,
            state: ImportFlowUiState,
            onReset: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                CompositionLocalProvider(LocalRouter provides router) {
                    ImportFlowRoute(router = router, openImportFlow = fixedImportFlow(state, onReset = onReset))
                }
            }

        val running =
            listOf(
                UploadBooksUiState.Uploading(fileIndex = 1, fileCount = 4, filename = "02.m4b", fraction = 0.4f),
                UploadBooksUiState.Finalizing,
            )
        running.forEach { state ->
            test("upload: leaving while ${state::class.simpleName} asks, and Stop upload cancels, then leaves") {
                val router = Router()
                try {
                    var cancelled = 0
                    val host = mountUpload(router, state) { cancelled++ }
                    awaitFrame()

                    router.navigate(Route(listOf("library")))
                    awaitFrame()

                    dialogText(host).contains("Stop upload?") shouldBe true
                    window.location.pathname shouldBe originalUrl.substringBefore('?')

                    discardDialogButton(host, "Stop upload").shouldNotBeNull().click()
                    awaitFrame()

                    cancelled shouldBe 1
                    router.current.segments shouldBe listOf("library")
                } finally {
                    router.dispose()
                }
            }
        }

        test("upload: keeping a running upload stays and does not cancel it") {
            val router = Router()
            try {
                var cancelled = 0
                val host = mountUpload(router, running.first()) { cancelled++ }
                awaitFrame()

                router.navigate(Route(listOf("library")))
                awaitFrame()
                discardDialogButton(host, "Cancel").shouldNotBeNull().click()
                awaitFrame()

                dialogText(host).contains("Stop upload?") shouldBe false
                cancelled shouldBe 0
                window.location.pathname shouldBe originalUrl.substringBefore('?')
            } finally {
                router.dispose()
            }
        }

        listOf(
            UploadBooksUiState.Idle,
            finished(),
            UploadBooksUiState.Error(InternalError(debugInfo = "boom")),
        ).forEach { state ->
            test("upload: a ${state::class.simpleName} page leaves at once") {
                val router = Router()
                try {
                    val host = mountUpload(router, state)
                    awaitFrame()

                    router.navigate(Route(listOf("library")))
                    awaitFrame()

                    dialogText(host).contains("Stop upload?") shouldBe false
                    router.current.segments shouldBe listOf("library")
                } finally {
                    router.dispose()
                }
            }
        }

        listOf(
            ImportFlowUiState.Uploading("backup.zip"),
            ImportFlowUiState.Analyzing(1, 10, null, 0, 0),
            review(),
            ImportFlowUiState.Applying(1, 10, null, 0),
        ).forEach { state ->
            test("import: leaving while ${state::class.simpleName} asks, and Leave import resets, then leaves") {
                val router = Router()
                try {
                    var reset = 0
                    val host = mountImport(router, state) { reset++ }
                    awaitFrame()

                    router.navigate(Route(listOf("library")))
                    awaitFrame()

                    dialogText(host).contains("Leave the import?") shouldBe true
                    window.location.pathname shouldBe originalUrl.substringBefore('?')

                    discardDialogButton(host, "Leave import").shouldNotBeNull().click()
                    awaitFrame()

                    reset shouldBe 1
                    router.current.segments shouldBe listOf("library")
                } finally {
                    router.dispose()
                }
            }
        }

        listOf(
            ImportFlowUiState.Idle,
            ImportFlowUiState.Done(importResult()),
            ImportFlowUiState.Error(InternalError(debugInfo = "boom")),
        ).forEach { state ->
            test("import: a ${state::class.simpleName} page leaves at once") {
                val router = Router()
                try {
                    val host = mountImport(router, state)
                    awaitFrame()

                    router.navigate(Route(listOf("library")))
                    awaitFrame()

                    dialogText(host).contains("Leave the import?") shouldBe false
                    router.current.segments shouldBe listOf("library")
                } finally {
                    router.dispose()
                }
            }
        }
    })
