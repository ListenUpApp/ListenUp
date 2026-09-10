package com.calypsan.listenup.web.features.books

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.design.DialogActions
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.disabledWhen
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** One destination a selection can be added to — a shelf or a collection, told apart by the caller. */
internal class PickerTarget(
    val id: String,
    val name: String,
    val subtitle: String?,
)

/**
 * Pick where the selected books go — or make somewhere new for them.
 *
 * ⛔ Both paths, in one dialog. A picker with no create path strands a reader whose first bulk
 * action is exactly the reason they want a new shelf, and a separate "new shelf" flow would make
 * them build it empty and come back. The ViewModel already has the create-and-add call; this is
 * the surface for it.
 */
@Composable
internal fun SelectionPicker(
    title: String,
    count: Int,
    targets: List<PickerTarget>,
    emptyMessage: String,
    createLabel: String,
    isBusy: Boolean,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }

    ModalDialog(open = true, title = title, onDismiss = onDismiss) {
        P(attrs = { classes("dlg-p") }) { Text(bookCountLabel(count)) }

        if (targets.isEmpty()) {
            P(attrs = { classes("sel-none") }) { Text(emptyMessage) }
        } else {
            Div(attrs = { classes("sel-targets") }) {
                targets.forEach { target ->
                    Button(attrs = {
                        classes("sel-target")
                        attr(ATTR_TYPE, VALUE_BUTTON)
                        disabledWhen(isBusy)
                        onClick { onPick(target.id) }
                    }) {
                        Span(attrs = { classes("sel-target-n") }) { Text(target.name) }
                        target.subtitle?.let { Span(attrs = { classes("sel-target-s") }) { Text(it) } }
                    }
                }
            }
        }

        Field(
            label = createLabel,
            value = newName,
            onInput = { newName = it },
            id = "sel-new-name",
        )
        DialogActions(
            confirmLabel = "Create and add",
            onConfirm = { onCreate(newName.trim()) },
            onDismiss = onDismiss,
            confirmEnabled = newName.isNotBlank() && !isBusy,
        )
    }
}

/** "1 book" / "37 books" — the number is the whole point of a bulk action. */
internal fun bookCountLabel(count: Int): String = if (count == 1) "1 book" else "$count books"

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"
