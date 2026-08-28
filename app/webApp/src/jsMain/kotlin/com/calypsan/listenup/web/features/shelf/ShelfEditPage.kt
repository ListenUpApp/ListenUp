package com.calypsan.listenup.web.features.shelf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfUiState
import com.calypsan.listenup.web.design.CheckboxField
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.TextAreaField
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * Create a shelf, or change one that exists.
 *
 * One page for both, because they differ only in what fills the fields first and whether Delete is
 * offered — and a second page would be the same form with a different heading, kept in sync by hand.
 *
 * The text lives here rather than in [CreateEditShelfUiState], exactly as the Compose screen keeps
 * it in `rememberSaveable`: a ViewModel that owned every keystroke would re-emit the whole state on
 * each one, and the shared VM deliberately does not.
 *
 * Deleting asks through the shared [ConfirmDialog]. It shipped as a two-step inline confirm because
 * web had no dialog primitive and inventing one inside a feature would have committed the design
 * system to an unreviewed pattern; "sign out everywhere" was the second destructive action, which is
 * where that pattern got decided. Two shapes of "are you sure" in one app was the thing to avoid.
 *
 * [CreateEditShelfUiState.Loaded] seeds the inputs by keying their `remember` on it. Re-seeding on
 * every emission would overwrite whatever the person had typed the moment anything else
 * re-rendered; an effect would seed a frame late, which is a blank form on first paint.
 */
@Composable
fun ShelfEditPage(
    state: CreateEditShelfUiState,
    isEditing: Boolean,
    onSave: (name: String, description: String, isPrivate: Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismissError: () -> Unit,
    onCancel: () -> Unit,
) {
    val loaded = state as? CreateEditShelfUiState.Loaded
    // Keyed on the loaded value, so the fields carry it from their very first composition rather
    // than a frame later — and so a re-emission of the SAME shelf (a data class, so an equal key)
    // leaves whatever has been typed alone. Only a genuinely different shelf re-seeds.
    var name by remember(loaded) { mutableStateOf(loaded?.name.orEmpty()) }
    var description by remember(loaded) { mutableStateOf(loaded?.description.orEmpty()) }
    var isPrivate by remember(loaded) { mutableStateOf(loaded?.isPrivate ?: false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val saving = state is CreateEditShelfUiState.Saving
    val heading = if (isEditing) "Edit shelf" else "New shelf"

    Div(attrs = { classes("shelf-edit") }) {
        H1(attrs = { classes("shelf-title") }) { Text(heading) }

        if (state is CreateEditShelfUiState.LoadingExisting) {
            Div(attrs = { classes("skel", "shelf-skel") })
            return@Div
        }

        // A real <form>, so Enter submits and the browser's own field semantics apply — the same
        // reason the auth screens are forms rather than divs full of inputs.
        val submit = onSave
        Form(attrs = {
            classes("shelf-form")
            this.onSubmit { event ->
                event.preventDefault()
                if (name.isNotBlank() && !saving) {
                    submit(name.trim(), description.trim(), isPrivate)
                }
            }
        }) {
            Field(
                label = "Name",
                value = name,
                onInput = { name = it },
                placeholder = "Comfort reads",
            )
            TextAreaField(
                label = "Description",
                value = description,
                onInput = { description = it },
                placeholder = "What is this shelf for?",
            )
            CheckboxField(
                label = "Private — only you can see this shelf",
                checked = isPrivate,
                onChange = { isPrivate = it },
            )

            (state as? CreateEditShelfUiState.Error)?.let { error ->
                P(attrs = { classes("shelf-form-error") }) { Text(error.message) }
                Button(attrs = {
                    classes(QUIET_BUTTON)
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    onClick { onDismissError() }
                }) { Text("Dismiss") }
            }

            Div(attrs = { classes("shelf-form-actions") }) {
                Button(attrs = {
                    classes("btn")
                    attr(ATTR_TYPE, "submit")
                    // Disabled on an empty name rather than validated after the fact: a shelf with
                    // no name is the one input this form genuinely cannot accept.
                    if (name.isBlank() || saving) attr("disabled", "")
                }) { Text(if (saving) "Saving…" else "Save") }

                Button(attrs = {
                    classes(QUIET_BUTTON)
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    onClick { onCancel() }
                }) { Text("Cancel") }
            }
        }

        if (isEditing) {
            DeleteShelfSection(
                saving = saving,
                confirming = confirmingDelete,
                onAsk = { confirmingDelete = true },
                onDismiss = { confirmingDelete = false },
                onDelete = onDelete,
            )
        }
    }
}

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"

/**
 * Deleting a shelf: a quiet way in, and the shared dialog for the actual question.
 *
 * Its own composable because inlining it pushed [ShelfEditPage] past its cognitive-complexity
 * limit — which was a fair complaint, not a metric to route around: the form and the destructive
 * action beside it are two different things to reason about.
 *
 * Set apart from the form above it, because deleting is not one of the form's actions. Putting it
 * in the same row as Save is how someone deletes a shelf they meant to rename.
 */
@Composable
private fun DeleteShelfSection(
    saving: Boolean,
    confirming: Boolean,
    onAsk: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    Div(attrs = { classes("shelf-danger") }) {
        Button(attrs = {
            classes(QUIET_BUTTON)
            attr(ATTR_TYPE, VALUE_BUTTON)
            if (saving) attr("disabled", "")
            onClick { onAsk() }
        }) { Text("Delete shelf") }
    }

    ConfirmDialog(
        open = confirming,
        title = "Delete this shelf?",
        // Answers the question someone deleting a grouping actually has.
        body = "The shelf is removed for everyone it was shared with. The books stay in your library.",
        confirmLabel = "Delete shelf",
        onConfirm = {
            onDismiss()
            onDelete()
        },
        onDismiss = onDismiss,
    )
}

/** The outline button. Every action on this page that is not the primary one wears it. */
private const val QUIET_BUTTON = "btn-o"
