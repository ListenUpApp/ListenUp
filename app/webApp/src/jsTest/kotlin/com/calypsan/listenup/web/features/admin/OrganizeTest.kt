package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.organize.OrganizeAuthorForm
import com.calypsan.listenup.api.dto.organize.OrganizePreset
import com.calypsan.listenup.api.dto.organize.OrganizePreviewDto
import com.calypsan.listenup.api.dto.organize.OrganizePreviewEntryDto
import com.calypsan.listenup.api.dto.organize.OrganizeSeriesPrefix
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.presentation.admin.OrganizeRunProgress
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

internal fun entry(
    bookId: String = "b1",
    fromPath: String = "Unsorted/dune.m4b",
    toPath: String = "Frank Herbert/Dune",
    collisionResolved: Boolean = false,
    renamedFrom: String? = null,
    renamedTo: String? = null,
) = OrganizePreviewEntryDto(
    bookId = bookId,
    fromPath = fromPath,
    toPath = toPath,
    collisionResolved = collisionResolved,
    renamedFrom = renamedFrom,
    renamedTo = renamedTo,
)

internal fun preview(
    bookCount: Int = 2,
    fileCount: Int = 5,
    collisionCount: Int = 0,
    entries: List<OrganizePreviewEntryDto> = listOf(entry()),
    truncated: Boolean = false,
    renamedInPlaceCount: Int = 0,
) = OrganizePreviewDto(
    bookCount = bookCount,
    fileCount = fileCount,
    collisionCount = collisionCount,
    entries = entries,
    truncated = truncated,
    renamedInPlaceCount = renamedInPlaceCount,
)

internal fun readyOrganize(
    settings: OrganizeSettingsDto = OrganizeSettingsDto(),
    isWorking: Boolean = false,
    previewDto: OrganizePreviewDto? = null,
    run: OrganizeRunProgress? = null,
    error: com.calypsan.listenup.api.error.AppError? = null,
) = OrganizeSettingsUiState.Ready(
    settings = settings,
    isWorking = isWorking,
    preview = previewDto,
    run = run,
    error = error,
)

internal fun noopOrganizeActions() =
    OrganizeActions(
        onPreset = {},
        onSeriesPrefix = {},
        onAuthorForm = {},
        onSaveRules = {},
        onOrganize = {},
        onConfirmOrganize = {},
        onDismissPreview = {},
        onDismissReport = {},
        onResume = {},
    )

private fun buttons(host: HTMLElement): List<HTMLButtonElement> =
    host.querySelectorAll("button").asList().filterIsInstance<HTMLButtonElement>()

private fun button(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? = buttons(host).firstOrNull { it.textContent?.trim() == label }

private fun text(
    host: HTMLElement,
    selector: String,
): String? = (host.querySelector(selector) as? HTMLElement)?.textContent?.trim()

/**
 * The file organizer.
 *
 * ⛔ The thing these specs exist to hold: **saving rules and rearranging a library are not the same
 * promise.** Save is live for future arrivals and moves nothing; Organize previews, asks, and only
 * then touches what is already there. Everything else here follows from keeping those two apart and
 * from the preview dialog describing the plan honestly.
 */
class OrganizeTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun page(
            state: OrganizeSettingsUiState,
            actions: OrganizeActions = noopOrganizeActions(),
            onOpenAdmin: () -> Unit = {},
        ): HTMLElement = mounts.mount { OrganizePage(state = state, actions = actions, onOpenAdmin = onOpenAdmin) }

        test("every rule is a set of choices, with the current one shown") {
            val host =
                page(
                    readyOrganize(
                        OrganizeSettingsDto(
                            preset = OrganizePreset.FLAT_TITLE,
                            seriesPrefix = OrganizeSeriesPrefix.NONE,
                            authorForm = OrganizeAuthorForm.LAST_FIRST,
                        ),
                    ),
                )

            buttons(host).filter { it.classList.contains("on") }.map { it.textContent?.trim() } shouldContainExactly
                listOf("Title only", "No number", "Last, First")
        }

        test("each rule reports its own change") {
            val presets = mutableListOf<OrganizePreset>()
            val prefixes = mutableListOf<OrganizeSeriesPrefix>()
            val forms = mutableListOf<OrganizeAuthorForm>()
            val host =
                page(
                    readyOrganize(),
                    noopOrganizeActions().copy(
                        onPreset = { presets += it },
                        onSeriesPrefix = { prefixes += it },
                        onAuthorForm = { forms += it },
                    ),
                )

            button(host, "Title only").shouldNotBeNull().click()
            button(host, "[1] Title").shouldNotBeNull().click()
            button(host, "Last, First").shouldNotBeNull().click()
            awaitFrame()

            presets shouldContainExactly listOf(OrganizePreset.FLAT_TITLE)
            prefixes shouldContainExactly listOf(OrganizeSeriesPrefix.BRACKET_N)
            forms shouldContainExactly listOf(OrganizeAuthorForm.LAST_FIRST)
        }

        test("saving says in words that nothing already here will move") {
            // ⛔ Not implied by button order. An admin who assumes Save sweeps the library either
            // never presses it, or presses it expecting a sweep.
            val host = page(readyOrganize())

            text(host, ".org-note").shouldNotBeNull() shouldContain "Nothing already in your library moves"
        }

        test("Save and Organize are separate actions, and each reports itself") {
            var saved = 0
            var organized = 0
            val host =
                page(readyOrganize(), noopOrganizeActions().copy(onSaveRules = { saved++ }, onOrganize = { organized++ }))

            button(host, "Save settings").shouldNotBeNull().click()
            button(host, "Organize library").shouldNotBeNull().click()
            awaitFrame()

            saved shouldBe 1
            organized shouldBe 1
        }

        test("work in flight holds both actions still") {
            val host = page(readyOrganize(isWorking = true))

            button(host, "Save settings").shouldNotBeNull().hasAttribute("disabled") shouldBe true
            button(host, "Organize library").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        test("nothing moves before the preview is confirmed") {
            var confirmed = 0
            var dismissed = 0
            val host =
                page(
                    readyOrganize(previewDto = preview()),
                    noopOrganizeActions().copy(
                        onConfirmOrganize = { confirmed++ },
                        onDismissPreview = { dismissed++ },
                    ),
                )

            button(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()
            confirmed shouldBe 0
            dismissed shouldBe 1

            button(host, "Organize now").shouldNotBeNull().click()
            awaitFrame()
            confirmed shouldBe 1
        }

        test("the preview leads with what it would move") {
            previewSummary(preview(bookCount = 3, fileCount = 12, collisionCount = 2)) shouldBe
                "Moves 12 files across 3 folders; 2 collisions resolved."
        }

        test("a plan of nothing but in-place renames leads with the renames") {
            // ⛔ `bookCount` and `fileCount` count relocations ONLY. Leading with "moves 0 files
            // across 0 folders" on a rename-only plan reports real work as a no-op.
            val summary = previewSummary(preview(bookCount = 0, fileCount = 0, renamedInPlaceCount = 4))

            summary shouldBe "Audio files renamed in place, to match the folder they are already in: 4."
            summary shouldNotContain "0 folders"
        }

        test("a mixed plan says both halves") {
            previewSummary(preview(bookCount = 2, fileCount = 5, renamedInPlaceCount = 3)) shouldContain "Moves 5 files"
            previewSummary(preview(bookCount = 2, fileCount = 5, renamedInPlaceCount = 3)) shouldContain "in place"
        }

        test("a relocation row shows folders; an in-place rename shows filenames") {
            // ⛔ The folder is unchanged on a rename row — rendering it on both sides would make a
            // real edit read as a no-op, which is the whole reason the DTO carries two filenames.
            rowText(entry(fromPath = "Unsorted/x", toPath = "Frank Herbert/Dune")) shouldBe
                ("Unsorted/x" to "Frank Herbert/Dune")
            rowText(
                entry(
                    fromPath = "Frank Herbert/Dune",
                    toPath = "Frank Herbert/Dune",
                    renamedFrom = "track01.m4b",
                    renamedTo = "Dune.m4b",
                ),
            ) shouldBe ("track01.m4b" to "Dune.m4b")
        }

        test("the preview shows its sample rows, flagging a name it had to resolve") {
            val host =
                page(
                    readyOrganize(
                        previewDto =
                            preview(
                                entries =
                                    listOf(
                                        entry(fromPath = "Unsorted/a", toPath = "Author/A"),
                                        entry(bookId = "b2", fromPath = "Unsorted/b", toPath = "Author/B", collisionResolved = true),
                                    ),
                            ),
                    ),
                )

            host.querySelectorAll(".org-row").length shouldBe 2
            host.querySelectorAll(".org-clash").length shouldBe 1
            host.querySelector(".org-more").shouldBeNull()
        }

        test("a truncated sample says there is more than it is showing") {
            val host = page(readyOrganize(previewDto = preview(truncated = true)))

            text(host, ".org-more").shouldNotBeNull()
        }

        test("a run in flight shows how far along it is") {
            val host = page(readyOrganize(run = OrganizeRunProgress(completed = 3, total = 12)))

            host.textContent.orEmpty() shouldContain "3 of 12 books"
            (host.querySelector(".org-bar-fill") as HTMLElement).getAttribute("style") shouldContain "25%"
        }

        test("a run with no total yet is zero, never a division by zero") {
            runFraction(OrganizeRunProgress(completed = 0, total = 0)) shouldBe 0
            runFraction(OrganizeRunProgress(completed = 6, total = 12)) shouldBe 50
            runFraction(OrganizeRunProgress(completed = 99, total = 12)) shouldBe 100
        }

        test("a finished run reports what it did") {
            reportSummary(OrganizeRunProgress(movedBooks = 9, failedBooks = 0, terminal = true)) shouldBe
                "9 books moved, 0 failed."

            var dismissed = 0
            val host =
                page(
                    readyOrganize(run = OrganizeRunProgress(movedBooks = 9, terminal = true)),
                    noopOrganizeActions().copy(onDismissReport = { dismissed++ }),
                )

            button(host, "Resume").shouldBeNull()
            button(host, "Done").shouldNotBeNull().click()
            awaitFrame()
            dismissed shouldBe 1
        }

        test("a partial failure offers to carry on with what is left") {
            // Never Stranded: the books that moved stay moved, and Resume re-plans the remainder.
            var resumed = 0
            val host =
                page(
                    readyOrganize(run = OrganizeRunProgress(movedBooks = 7, failedBooks = 2, terminal = true)),
                    noopOrganizeActions().copy(onResume = { resumed++ }),
                )

            button(host, "Resume").shouldNotBeNull().click()
            awaitFrame()
            resumed shouldBe 1
        }

        test("a failed rule change says so without emptying the page") {
            val host = page(readyOrganize(error = InternalError(debugInfo = "boom")))

            text(host, ".org-err").shouldNotBeNull()
            button(host, "Save settings").shouldNotBeNull()
        }

        test("settings that could not be loaded are an error, not an empty form") {
            val host = page(OrganizeSettingsUiState.Error(InternalError(debugInfo = "boom")))

            text(host, ".org-none").shouldNotBeNull()
            button(host, "Organize library").shouldBeNull()
        }

        test("every label is the shape it describes, not the enum's name") {
            presetLabel(OrganizePreset.AUTHOR_SERIES_TITLE) shouldBe "Author / Series / Title"
            prefixLabel(OrganizeSeriesPrefix.BOOK_N_DASH) shouldBe "Book 1 - Title"
            authorLabel(OrganizeAuthorForm.FIRST_LAST) shouldBe "First Last"
        }
    })
