package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.presentation.admin.RestoreBackupUiState
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private val hosts = mutableListOf<HTMLElement>()

internal fun restoreResult(
    includedImages: Boolean = true,
    from: String = "7",
    to: String = "7",
): RestoreResult =
    RestoreResult(
        restoredFrom = BackupId("bk1"),
        includedImages = includedImages,
        schemaMigratedFrom = from,
        schemaMigratedTo = to,
    )

private fun page(
    state: RestoreBackupUiState,
    progress: BackupEvent? = null,
    onRequest: () -> Unit = {},
    onCancel: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onOpenBackups: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        RestorePage(
            state = state,
            progress = progress,
            onRequest = onRequest,
            onCancel = onCancel,
            onConfirm = onConfirm,
            onOpenBackups = onOpenBackups,
        )
    }
    return host
}

private fun dialogButton(
    host: HTMLElement,
    label: String,
) = host
    .querySelectorAll("dialog.dlg button")
    .asList()
    .filterIsInstance<HTMLElement>()
    .firstOrNull { it.textContent?.trim() == label }

/**
 * The restore page.
 *
 * What these pin: the confirmation is the ViewModel's state rather than a dialog this page opens,
 * so the page cannot start a restore by rendering; the way out disappears while the server is
 * swapping its own database, because leaving is how you miss the outcome; and every phase the
 * server can stream has a sentence, including the rollback — this is the only page watching.
 */
class RestorePageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the idle page says what a restore costs before offering one") {
            val host = page(RestoreBackupUiState.Idle())

            val text = host.textContent.orEmpty()
            text shouldContain "replaces this server's whole database"
            text shouldContain "Anything added since it was taken is gone"
        }

        // ⛔ Pressing Restore asks the ViewModel; it does not open a dialog here. That is what
        // stops a reload mid-decision from landing half-committed.
        test("Restore asks the ViewModel rather than opening its own dialog") {
            var requested = 0
            val host = page(RestoreBackupUiState.Idle(), onRequest = { requested++ })

            (host.querySelector(".btn-c") as HTMLElement).click()
            awaitFrame()

            requested shouldBe 1
            host.querySelector("dialog.dlg").shouldBeNull()
        }

        test("the confirmation names what is lost and who is not told") {
            val host = page(RestoreBackupUiState.Confirming)

            val text = host.querySelector("dialog.dlg")?.textContent.orEmpty()
            text shouldContain "will be lost"
            text shouldContain "will not be told"
            text shouldContain "no undo"
        }

        test("confirming confirms; cancelling cancels") {
            var confirmed = 0
            var cancelled = 0
            val host = page(RestoreBackupUiState.Confirming, onConfirm = { confirmed++ }, onCancel = { cancelled++ })

            dialogButton(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()
            dialogButton(host, "Replace everything").shouldNotBeNull().click()
            awaitFrame()

            cancelled shouldBe 1
            confirmed shouldBe 1
        }

        test("a prior failure is shown on the idle page, and announced") {
            val host = page(RestoreBackupUiState.Idle(error = InternalError(debugInfo = "boom")))

            host.querySelector(".rst-err").shouldNotBeNull().getAttribute("role") shouldBe "alert"
        }

        // Leaving is how you miss the outcome, and the server is offline while it happens.
        test("there is no way off the page while the restore runs") {
            val running = page(RestoreBackupUiState.Restoring)
            val idle = page(RestoreBackupUiState.Idle())

            running.querySelector(".rst-back").shouldBeNull()
            idle.querySelector(".rst-back").shouldNotBeNull()
        }

        test("the running page announces its phase politely") {
            val host = page(RestoreBackupUiState.Restoring, progress = BackupEvent.Swapping)

            val live = host.querySelector(".rst-live").shouldNotBeNull()
            live.getAttribute("role") shouldBe "status"
            live.getAttribute("aria-live") shouldBe "polite"
        }

        test("each restore phase says something different") {
            val phases =
                listOf(
                    null,
                    BackupEvent.Validating,
                    BackupEvent.Draining,
                    BackupEvent.Swapping,
                    BackupEvent.Migrating,
                )
            val labels =
                phases.map { phase ->
                    page(RestoreBackupUiState.Restoring, progress = phase)
                        .querySelector(".rst-phase")
                        ?.textContent
                        .orEmpty()
                }

            labels.toSet().size shouldBe phases.size
        }

        // A rollback is the outcome nobody is watching for, so it has to say so here and say why.
        test("a rollback says it failed and gives the reason") {
            val host =
                page(
                    RestoreBackupUiState.Restoring,
                    progress = BackupEvent.RolledBack(reason = "checksum mismatch"),
                )

            val text = host.querySelector(".rst-phase")?.textContent.orEmpty()
            text shouldContain "old database was put back"
            text shouldContain "checksum mismatch"
        }

        test("a completed restore says whether the covers came with it") {
            val withImages = page(RestoreBackupUiState.Completed(restoreResult(includedImages = true)))
            val without = page(RestoreBackupUiState.Completed(restoreResult(includedImages = false)))

            withImages.textContent.orEmpty() shouldContain "and its covers are back"
            without.textContent.orEmpty() shouldContain "carried no covers"
        }

        // A restore that migrated across schema versions is the case where something might behave
        // differently afterwards, so that is the one detail worth surfacing.
        test("a migrated restore says which versions; an unmigrated one stays quiet") {
            val migrated = page(RestoreBackupUiState.Completed(restoreResult(from = "6", to = "7")))
            val same = page(RestoreBackupUiState.Completed(restoreResult(from = "7", to = "7")))

            migrated.querySelector(".rst-schema")?.textContent.orEmpty() shouldContain "from schema 6 to 7"
            same.querySelector(".rst-schema").shouldBeNull()
            same.textContent.orEmpty() shouldNotContain "schema"
        }

        test("a completed restore offers the way back, and it fires") {
            var back = 0
            val host = page(RestoreBackupUiState.Completed(restoreResult()), onOpenBackups = { back++ })

            (host.querySelector(".empty button") as HTMLElement).click()
            awaitFrame()

            back shouldBe 1
        }
    })
