package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.domain.model.BackupInfo
import com.calypsan.listenup.client.presentation.admin.AdminBackupUiState
import com.calypsan.listenup.client.presentation.admin.RestoreFromFileUiState
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList

private val hosts = mutableListOf<HTMLElement>()

private const val ONE_MB = 1024L * 1024

internal fun backup(
    id: String = "bk1",
    size: Long = ONE_MB,
    createdAtMs: Long = 1_800_000_000_000L,
): BackupInfo = BackupInfo(id = id, size = size, createdAt = Timestamp(createdAtMs))

internal fun readyBackups(
    backups: List<BackupInfo> = listOf(backup()),
    isCreating: Boolean = false,
    isDeleting: Boolean = false,
    error: com.calypsan.listenup.api.error.AppError? = null,
    deleteConfirmBackup: BackupInfo? = null,
): AdminBackupUiState.Ready =
    AdminBackupUiState.Ready(
        backups = backups,
        isCreating = isCreating,
        isDeleting = isDeleting,
        error = error,
        deleteConfirmBackup = deleteConfirmBackup,
    )

@Suppress("LongParameterList")
private fun page(
    state: AdminBackupUiState,
    uploadState: RestoreFromFileUiState = RestoreFromFileUiState.Idle,
    onCreate: (Boolean) -> Unit = {},
    onDownload: (BackupInfo) -> Unit = {},
    onAskDelete: (BackupInfo) -> Unit = {},
    onDismissDelete: () -> Unit = {},
    onDelete: (BackupInfo) -> Unit = {},
    onResetUpload: () -> Unit = {},
    onClearError: () -> Unit = {},
    onRetry: () -> Unit = {},
    onRestore: (BackupInfo) -> Unit = {},
    onOpenAdmin: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        BackupsPage(
            state = state,
            uploadState = uploadState,
            onCreate = onCreate,
            onDownload = onDownload,
            onAskDelete = onAskDelete,
            onDismissDelete = onDismissDelete,
            onDelete = onDelete,
            onPickFile = {},
            onResetUpload = onResetUpload,
            onClearError = onClearError,
            onRetry = onRetry,
            onRestore = onRestore,
            onOpenAdmin = onOpenAdmin,
        )
    }
    return host
}

private fun button(
    host: HTMLElement,
    label: String,
) = host
    .querySelectorAll("button")
    .asList()
    .filterIsInstance<HTMLElement>()
    .firstOrNull { it.textContent?.trim() == label }

private fun labelledPrefix(
    host: HTMLElement,
    prefix: String,
) = host
    .querySelectorAll("button[aria-label]")
    .asList()
    .filterIsInstance<HTMLElement>()
    .firstOrNull { it.getAttribute("aria-label")?.startsWith(prefix) == true }

private fun dialogButton(
    host: HTMLElement,
    label: String,
) = host
    .querySelectorAll("dialog.dlg button")
    .asList()
    .filterIsInstance<HTMLElement>()
    .firstOrNull { it.textContent?.trim() == label }

/**
 * The backups screen.
 *
 * What these pin: deleting says the library survives, since "delete backup" next to a list of dates
 * is easy to misread as deleting what is in it; Restore is a link rather than a button, because a
 * restore replaces the whole database and that decision gets its own page; and the covers checkbox
 * says what it costs, which is the only reason it is a choice.
 */
class BackupsPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        // ⛔ 1.5 MB, not 1 MB. `sizeFormatted` computes a Double, and Kotlin/JS renders `1.0`
        // as "1" where the JVM renders "1.0" — so a whole-number size would make this spec assert
        // a platform's number formatting rather than that the label reaches the row.
        test("a backup shows when it was taken and how big it is") {
            val host = page(readyBackups(listOf(backup(size = ONE_MB + ONE_MB / 2))))

            host
                .querySelector(".bkp-when")
                ?.textContent
                .orEmpty()
                .isNotBlank() shouldBe true
            host.querySelector(".bkp-size")?.textContent shouldBe "1.5 MB"
        }

        test("backing up reports whether covers go with it") {
            val asked = mutableListOf<Boolean>()
            val host = page(readyBackups(), onCreate = { asked += it })

            // Default on, then turned off — so a callback hardcoded either way cannot pass.
            button(host, "Back up now").shouldNotBeNull().click()
            awaitFrame()
            val box = host.querySelector("#bkp-images") as HTMLInputElement
            box.click()
            awaitFrame()
            button(host, "Back up now").shouldNotBeNull().click()
            awaitFrame()

            asked shouldContainExactly listOf(true, false)
        }

        // The size difference is the whole reason this is a choice.
        test("the covers checkbox says what leaving them out buys") {
            val host = page(readyBackups())

            host.textContent.orEmpty() shouldContain "much smaller file"
        }

        test("a backup in flight says so and cannot be started again") {
            val host = page(readyBackups(isCreating = true))

            val make = button(host, "Backing up…").shouldNotBeNull()
            make.hasAttribute("disabled") shouldBe true
        }

        test("downloading reports which backup") {
            val downloaded = mutableListOf<String>()
            val host = page(readyBackups(listOf(backup(id = "bk7"))), onDownload = { downloaded += it.id })

            labelledPrefix(host, "Download the backup").shouldNotBeNull().click()
            awaitFrame()

            downloaded shouldContainExactly listOf("bk7")
        }

        // ⛔ Restore is a route, not an action here: replacing the whole database deserves its own
        // page rather than a dialog over a list.
        test("Restore leaves for its own page rather than starting one") {
            val restored = mutableListOf<String>()
            val host = page(readyBackups(listOf(backup(id = "bk7"))), onRestore = { restored += it.id })

            button(host, "Restore").shouldNotBeNull().click()
            awaitFrame()

            restored shouldContainExactly listOf("bk7")
            host.querySelector("dialog.dlg").shouldBeNull()
        }

        test("deleting asks first, and says the library survives") {
            val host = page(readyBackups(deleteConfirmBackup = backup()))

            val text = host.querySelector("dialog.dlg")?.textContent.orEmpty()
            text shouldContain "Your library is untouched"
            text shouldContain "deletes the copy, not the books"
        }

        // The confirmation is ViewModel state, so pressing the trash asks the ViewModel to open it
        // rather than opening it here.
        test("the trash asks the ViewModel to open the confirmation") {
            val asked = mutableListOf<String>()
            val host = page(readyBackups(listOf(backup(id = "bk7"))), onAskDelete = { asked += it.id })

            labelledPrefix(host, "Delete the backup").shouldNotBeNull().click()
            awaitFrame()

            asked shouldContainExactly listOf("bk7")
            host.querySelector("dialog.dlg").shouldBeNull()
        }

        test("confirming deletes the backup that was asked about; cancelling does not") {
            val deleted = mutableListOf<String>()
            var dismissed = 0
            val host =
                page(
                    readyBackups(deleteConfirmBackup = backup(id = "bk7")),
                    onDelete = { deleted += it.id },
                    onDismissDelete = { dismissed++ },
                )

            dialogButton(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()
            dismissed shouldBe 1
            deleted shouldContainExactly emptyList()

            dialogButton(host, "Delete").shouldNotBeNull().click()
            awaitFrame()
            deleted shouldContainExactly listOf("bk7")
        }

        test("a delete in flight disables the trash") {
            val host = page(readyBackups(isDeleting = true))

            labelledPrefix(host, "Delete the backup").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        test("an upload in flight names the file and cannot be started again") {
            val host = page(readyBackups(), uploadState = RestoreFromFileUiState.Uploading("library.listenup.zip"))

            val pick = button(host, "Uploading library.listenup.zip…").shouldNotBeNull()
            pick.hasAttribute("disabled") shouldBe true
        }

        // The upload's failure is its own, and dismissing it resets the upload rather than the
        // list's error — two notices, two callbacks.
        test("a failed upload is reported and dismisses to a fresh pick") {
            var reset = 0
            val host =
                page(
                    readyBackups(),
                    uploadState = RestoreFromFileUiState.Error(InternalError(debugInfo = "boom")),
                    onResetUpload = { reset++ },
                )

            host.querySelector(".bkp-err-t").shouldNotBeNull()
            (host.querySelector(".bkp-err-x") as HTMLElement).click()
            awaitFrame()

            reset shouldBe 1
        }

        test("a list failure is reported, announced, and dismisses on its own callback") {
            var cleared = 0
            val host = page(readyBackups(error = InternalError(debugInfo = "boom")), onClearError = { cleared++ })

            host.querySelector(".bkp-err-t").shouldNotBeNull().getAttribute("role") shouldBe "alert"
            (host.querySelector(".bkp-err-x") as HTMLElement).click()
            awaitFrame()

            cleared shouldBe 1
        }

        test("an empty list says what a backup is") {
            val host = page(readyBackups(backups = emptyList()))

            host.querySelector(".empty")?.textContent.orEmpty() shouldContain "single file"
        }

        test("a loading list draws a skeleton") {
            val host = page(AdminBackupUiState.Loading)

            host.querySelector(".bkp-skel").shouldNotBeNull()
            host.querySelector(".bkp-row").shouldBeNull()
        }

        test("a list that cannot be loaded explains itself and offers a retry that fires") {
            var retries = 0
            val host = page(AdminBackupUiState.Error(InternalError(debugInfo = "boom")), onRetry = { retries++ })

            host.querySelector(".bkp-row").shouldBeNull()
            (host.querySelector(".empty button") as HTMLElement).click()
            awaitFrame()

            retries shouldBe 1
        }
    })
