package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.presentation.admin.genreMoveCandidates
import com.calypsan.listenup.web.design.DialogActions
import com.calypsan.listenup.web.design.DialogText
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.SelectField
import com.calypsan.listenup.web.design.SelectOption

/**
 * Which dialog Categories currently has open, if any.
 *
 * One sealed value rather than five booleans plus five "which genre" fields. Five independent flags
 * can represent two dialogs open at once — a state `showModal()` makes unreachable, so the type
 * should not be able to describe it either.
 */
internal sealed interface CategoryDialog {
    /** New genre, under [parent] — or at the top level when that is null. */
    data class Create(
        val parent: Genre?,
    ) : CategoryDialog

    data class Rename(
        val genre: Genre,
    ) : CategoryDialog

    data class Delete(
        val genre: Genre,
    ) : CategoryDialog

    data class Move(
        val genre: Genre,
    ) : CategoryDialog

    data class Merge(
        val genre: Genre,
    ) : CategoryDialog
}

/**
 * Create or rename: one text field and a verb.
 *
 * Both are the same dialog with different words, and saying so here is cheaper than two files that
 * drift over whether Enter submits. It does: the field is inside a form whose submit is the verb.
 */
@Composable
internal fun NameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    hint: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Seeded once from the genre being renamed; after that the field is the truth. Keyed on the
    // initial value so reopening the dialog on a different genre reseeds rather than keeping the
    // last one's text.
    var name by remember(initialName) { mutableStateOf(initialName) }
    val trimmed = name.trim()

    ModalDialog(open = true, title = title, onDismiss = onDismiss) {
        hint?.let { DialogText(it) }
        Field(
            label = "Name",
            value = name,
            onInput = { name = it },
            id = "cat-name",
        )
        // A genre with no name is not a genre, so the verb is disabled rather than pressable.
        DialogActions(
            confirmLabel = confirmLabel,
            onConfirm = { onConfirm(trimmed) },
            onDismiss = onDismiss,
            confirmEnabled = trimmed.isNotEmpty(),
        )
    }
}

/**
 * Move a genre under a different parent.
 *
 * ⛔ The candidate list is [genreMoveCandidates], not "every other genre". Moving a genre under its
 * own descendant would make a cycle, and that rule lives in commonMain where both native clients
 * already read it — a web page that filtered the list itself would be a second opinion about the
 * shape of the tree.
 */
@Composable
internal fun MoveDialog(
    genre: Genre,
    all: List<Genre>,
    onConfirm: (newParentId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var target by remember(genre.id) { mutableStateOf(TOP_LEVEL) }

    ModalDialog(open = true, title = "Move ${genre.name}", onDismiss = onDismiss) {
        DialogText("Choose where ${genre.name} should sit. Its own children move with it.")
        SelectField(
            label = "New parent",
            value = target,
            options =
                listOf(SelectOption(TOP_LEVEL, "Top level")) +
                    genreMoveCandidates(all, genre).map { SelectOption(it.id, it.pathLabel()) },
            onSelect = { target = it.orEmpty() },
            id = "cat-move-target",
        )
        DialogActions(
            confirmLabel = "Move",
            onConfirm = { onConfirm(target.takeIf { it != TOP_LEVEL }) },
            onDismiss = onDismiss,
        )
    }
}

/**
 * Merge one genre into another.
 *
 * The books move and the source genre stops existing, so the sentence says both. There is no
 * "merge into itself" option for the same reason the move dialog excludes descendants — an
 * operation that cannot mean anything should not be offered.
 */
@Composable
internal fun MergeDialog(
    genre: Genre,
    all: List<Genre>,
    onConfirm: (targetId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = remember(genre.id, all) { all.filter { it.id != genre.id } }
    var target by remember(genre.id) { mutableStateOf(candidates.firstOrNull()?.id.orEmpty()) }

    ModalDialog(open = true, title = "Merge ${genre.name}", onDismiss = onDismiss) {
        if (candidates.isEmpty()) {
            DialogText("There is nothing else to merge ${genre.name} into.")
        } else {
            DialogText(
                "Every book in ${genre.name} moves to the genre you pick, and ${genre.name} " +
                    "stops existing. Nothing on disk changes.",
            )
            SelectField(
                label = "Merge into",
                value = target,
                options = candidates.map { SelectOption(it.id, it.pathLabel()) },
                onSelect = { target = it.orEmpty() },
                id = "cat-merge-target",
            )
        }
        DialogActions(
            confirmLabel = "Merge",
            onConfirm = { onConfirm(target) },
            onDismiss = onDismiss,
            confirmEnabled = candidates.isNotEmpty() && target.isNotEmpty(),
        )
    }
}

/**
 * How a genre reads in a picker: its ancestry, then its name.
 *
 * Two genres can share a name at different points in the tree — "Classics" under Fiction and under
 * Non-fiction is the normal case, not a pathology — so the bare name is not enough to pick by.
 */
internal fun Genre.pathLabel(): String = parentPath?.let { "$it > $name" } ?: name

/** The select's stand-in for "no parent"; an empty option value is indistinguishable from unset. */
private const val TOP_LEVEL = "__top__"
