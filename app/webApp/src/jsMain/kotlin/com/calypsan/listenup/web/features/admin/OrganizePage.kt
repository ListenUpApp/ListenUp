package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.organize.OrganizeAuthorForm
import com.calypsan.listenup.api.dto.organize.OrganizePreset
import com.calypsan.listenup.api.dto.organize.OrganizePreviewDto
import com.calypsan.listenup.api.dto.organize.OrganizePreviewEntryDto
import com.calypsan.listenup.api.dto.organize.OrganizeSeriesPrefix
import com.calypsan.listenup.client.presentation.admin.OrganizeRunProgress
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.DialogActions
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.Panel
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Where books live on disk, and the one explicit action that moves them.
 *
 * ⛔ **Two actions that must not look alike.** Save persists the rules and moves nothing — they
 * govern what arrives from then on. Organize previews a plan, asks, and only then rearranges an
 * existing library. Wording them as a pair of equal buttons would make a destructive sweep one slip
 * away from a settings save.
 */
@Composable
fun OrganizePage(
    state: OrganizeSettingsUiState,
    actions: OrganizeActions,
    onOpenAdmin: () -> Unit,
) {
    Div(attrs = { classes("org") }) {
        Breadcrumb(trail = listOf("Admin", "File organization"), onNavigate = { onOpenAdmin() })
        H1(attrs = { classes("org-t") }) { Text("File organization") }
        P(attrs = { classes("org-lede") }) { Text("Keep library folders tidy and consistent.") }

        when (state) {
            OrganizeSettingsUiState.Loading -> Div(attrs = { classes("skel", "org-skel") })
            is OrganizeSettingsUiState.Error -> P(attrs = { classes("org-none") }) { Text(state.error.message) }
            is OrganizeSettingsUiState.Ready -> ReadyOrganize(state, actions)
        }
    }
}

/** Every callback the organizer screen owns, as one value — eleven parameters is not a signature. */
data class OrganizeActions(
    val onPreset: (OrganizePreset) -> Unit,
    val onSeriesPrefix: (OrganizeSeriesPrefix) -> Unit,
    val onAuthorForm: (OrganizeAuthorForm) -> Unit,
    val onSaveRules: () -> Unit,
    val onOrganize: () -> Unit,
    val onConfirmOrganize: () -> Unit,
    val onDismissPreview: () -> Unit,
    val onDismissReport: () -> Unit,
    val onResume: () -> Unit,
)

@Composable
private fun ReadyOrganize(
    state: OrganizeSettingsUiState.Ready,
    actions: OrganizeActions,
) {
    val settings = state.settings

    Panel(title = "Folder structure") {
        Div(attrs = { classes("org-opts") }) {
            OrganizePreset.entries.forEach { preset ->
                Choice(presetLabel(preset), preset == settings.preset) { actions.onPreset(preset) }
            }
        }
    }

    Panel(title = "Series number style") {
        Div(attrs = { classes("org-opts") }) {
            OrganizeSeriesPrefix.entries.forEach { prefix ->
                Choice(prefixLabel(prefix), prefix == settings.seriesPrefix) { actions.onSeriesPrefix(prefix) }
            }
        }
    }

    Panel(title = "Author name style") {
        Div(attrs = { classes("org-opts") }) {
            OrganizeAuthorForm.entries.forEach { form ->
                Choice(authorLabel(form), form == settings.authorForm) { actions.onAuthorForm(form) }
            }
        }
    }

    state.error?.let { failure -> P(attrs = { classes("org-err") }) { Text(failure.message) } }

    Div(attrs = { classes("org-actions") }) {
        Button(attrs = {
            classes("btn-c")
            attr(ATTR_TYPE, VALUE_BUTTON)
            if (state.isWorking) attr(ATTR_DISABLED, "")
            onClick { actions.onSaveRules() }
        }) { Text("Save settings") }
        Button(attrs = {
            classes("btn-o")
            attr(ATTR_TYPE, VALUE_BUTTON)
            if (state.isWorking) attr(ATTR_DISABLED, "")
            onClick { actions.onOrganize() }
        }) { Text("Organize library") }
    }
    // ⛔ Said in words, not implied by button order: Save changes where *future* books land and
    // moves nothing that is already here. An admin who assumes otherwise either never presses it
    // or presses it expecting a sweep.
    P(attrs = { classes("org-note") }) {
        Text("Saving applies these rules to books added from now on. Nothing already in your library moves.")
    }

    state.preview?.let { preview ->
        PreviewDialog(preview, actions.onConfirmOrganize, actions.onDismissPreview)
    }
    state.run?.let { run -> RunDialog(run, actions.onDismissReport, actions.onResume) }
}

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Button(attrs = {
        classes("org-opt")
        if (selected) classes("on")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr("aria-pressed", selected.toString())
        onClick { onSelect() }
    }) { Text(label) }
}

/** The consent dialog: what the sweep would do, and a sample of it. */
@Composable
private fun PreviewDialog(
    preview: OrganizePreviewDto,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalDialog(open = true, title = "Organize library?", onDismiss = onDismiss) {
        P(attrs = { classes("dlg-p") }) { Text(previewSummary(preview)) }
        Div(attrs = { classes("org-rows") }) {
            preview.entries.forEach { entry -> PreviewRow(entry) }
            if (preview.truncated) {
                P(attrs = { classes("org-more") }) { Text("…and more") }
            }
        }
        DialogActions(confirmLabel = "Organize now", onConfirm = onConfirm, onDismiss = onDismiss)
    }
}

/**
 * One before→after row.
 *
 * ⛔ An in-place rename shows its two *filenames*, never its folder: the folder is unchanged there,
 * and rendering it on both sides would make a real edit read as a no-op.
 */
@Composable
private fun PreviewRow(entry: OrganizePreviewEntryDto) {
    val (before, after) = rowText(entry)
    Div(attrs = { classes("org-row") }) {
        Span(attrs = { classes("org-from") }) { Text(before) }
        Span(attrs = { classes("org-arrow") }) { Text("→") }
        Span(attrs = { classes("org-to") }) { Text(after) }
        if (entry.collisionResolved) {
            Span(attrs = { classes("org-clash") }) { Text("name taken") }
        }
    }
}

/** Progress while it runs, and the report when it stops. */
@Composable
private fun RunDialog(
    run: OrganizeRunProgress,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
) {
    val title = if (run.terminal) "Library organized" else "Organizing library…"
    ModalDialog(open = true, title = title, onDismiss = onDismiss) {
        if (run.terminal) {
            P(attrs = { classes("dlg-p") }) { Text(reportSummary(run)) }
            Div(attrs = { classes("dlg-actions") }) {
                if (run.hasFailures) {
                    Button(attrs = {
                        classes("btn-o")
                        attr(ATTR_TYPE, VALUE_BUTTON)
                        onClick { onResume() }
                    }) { Text("Resume") }
                }
                Button(attrs = {
                    classes("btn-c")
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    onClick { onDismiss() }
                }) { Text("Done") }
            }
            return@ModalDialog
        }
        P(attrs = { classes("dlg-p") }) { Text("${run.completed} of ${run.total} books") }
        Div(attrs = { classes("org-bar") }) {
            Div(attrs = {
                classes("org-bar-fill")
                style { property("width", "${runFraction(run)}%") }
            })
        }
    }
}

/**
 * What the consent dialog leads with.
 *
 * ⛔ A plan of nothing but in-place renames counts **zero** folders — `bookCount` and `fileCount`
 * deliberately count relocations only. Leading with "moves 0 files across 0 folders" there would
 * report real work as a no-op, so the renames lead instead.
 */
internal fun previewSummary(preview: OrganizePreviewDto): String {
    val moves =
        "Moves ${preview.fileCount} files across ${preview.bookCount} folders; " +
            "${preview.collisionCount} collisions resolved."
    val renames =
        "Audio files renamed in place, to match the folder they are already in: " +
            "${preview.renamedInPlaceCount}."
    return when {
        preview.bookCount == 0 && preview.renamedInPlaceCount > 0 -> renames
        preview.renamedInPlaceCount > 0 -> "$moves $renames"
        else -> moves
    }
}

/** The two sides of one preview row — folders for a relocation, filenames for an in-place rename. */
internal fun rowText(entry: OrganizePreviewEntryDto): Pair<String, String> {
    val from = entry.renamedFrom
    val to = entry.renamedTo
    return if (from != null && to != null) from to to else entry.fromPath to entry.toPath
}

internal fun reportSummary(run: OrganizeRunProgress): String =
    "${run.movedBooks} books moved, ${run.failedBooks} failed."

/**
 * Percent complete. A run with no total yet is 0, never a division by zero.
 *
 * ⛔ The `total <= 0` guard is **not** provable by sabotage on this platform, and that is a
 * property of Kotlin/JS rather than a gap: integer division there compiles to `(a / b) | 0`, so
 * `0 / 0` is `NaN | 0` — which is 0, the very answer the guard returns. Removing it leaves every
 * spec green. It stays because a progress bar quietly depending on `NaN` coercing to zero is worse
 * than one that says what it means, and because this expression is not JS-only by intent. Same
 * shape as `SeriesChips`' note on `sequenceLabel`: a uniformity rule here, not a guarded one.
 */
internal fun runFraction(run: OrganizeRunProgress): Int =
    if (run.total <= 0) 0 else (run.completed * PERCENT / run.total).coerceIn(0, PERCENT)

internal fun presetLabel(preset: OrganizePreset): String =
    when (preset) {
        OrganizePreset.AUTHOR_TITLE -> "Author / Title"
        OrganizePreset.AUTHOR_SERIES_TITLE -> "Author / Series / Title"
        OrganizePreset.FLAT_TITLE -> "Title only"
    }

internal fun prefixLabel(prefix: OrganizeSeriesPrefix): String =
    when (prefix) {
        OrganizeSeriesPrefix.BOOK_N_DASH -> "Book 1 - Title"
        OrganizeSeriesPrefix.N_DASH -> "1 - Title"
        OrganizeSeriesPrefix.BRACKET_N -> "[1] Title"
        OrganizeSeriesPrefix.NONE -> "No number"
    }

internal fun authorLabel(form: OrganizeAuthorForm): String =
    when (form) {
        OrganizeAuthorForm.FIRST_LAST -> "First Last"
        OrganizeAuthorForm.LAST_FIRST -> "Last, First"
    }

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"

private const val ATTR_DISABLED = "disabled"

private const val PERCENT = 100
