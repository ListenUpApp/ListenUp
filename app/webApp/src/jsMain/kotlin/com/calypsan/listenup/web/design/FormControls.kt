package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea

/**
 * The controls a form needs beyond [Field], in the same controlled style: [value] is the truth and
 * the change callback is the only way it moves, so form state lives in one place.
 *
 * They deliberately reuse `.f-wrap` / `.f-label` / `.f-box` rather than growing a parallel
 * vocabulary — a select and a text input that disagree about their focus ring read as two
 * different design systems on one page.
 */
@Composable
fun TextAreaField(
    label: String,
    value: String,
    onInput: (String) -> Unit,
    rows: Int = TEXTAREA_ROWS,
    placeholder: String = "",
    id: String? = null,
) {
    val fieldId = rememberFieldId(id)
    Div(attrs = { classes("f-wrap") }) {
        Label(attrs = {
            classes("f-label")
            attr("for", fieldId)
        }) { Text(label) }
        Div(attrs = { classes("f-box", "f-box-area") }) {
            TextArea(value = value) {
                classes("f-input", "f-area")
                attr("rows", rows.toString())
                if (placeholder.isNotEmpty()) attr("placeholder", placeholder)
                attr("id", fieldId)
                onInput { event -> onInput(event.value) }
            }
        }
    }
}

/** One choice in a [SelectField]: the value that is stored, and the text a reader sees. */
data class SelectOption(
    val value: String,
    val label: String,
)

/**
 * A single-choice picker.
 *
 * [value] is nullable and [emptyLabel] names the unset case, because "no language recorded" is a
 * real and common state for imported metadata — offering only concrete languages would force the
 * reader to invent one.
 */
@Composable
fun SelectField(
    label: String,
    value: String?,
    options: List<SelectOption>,
    onSelect: (String?) -> Unit,
    emptyLabel: String = "—",
    id: String? = null,
) {
    val fieldId = rememberFieldId(id)
    Div(attrs = { classes("f-wrap") }) {
        Label(attrs = {
            classes("f-label")
            attr("for", fieldId)
        }) { Text(label) }
        Div(attrs = { classes("f-box") }) {
            Select(attrs = {
                classes("f-input", "f-select")
                attr("id", fieldId)
                onChange { event -> onSelect(event.value?.takeIf { it.isNotEmpty() }) }
            }) {
                Option(value = "", attrs = { if (value == null) selected() }) { Text(emptyLabel) }
                options.forEach { option ->
                    Option(value = option.value, attrs = { if (option.value == value) selected() }) {
                        Text(option.label)
                    }
                }
            }
        }
    }
}

/**
 * A checkbox with its label, wired as one control.
 *
 * The `<label>` wraps the input rather than pointing at it by id, so the text is part of the click
 * target without every call site having to invent a unique id for it.
 */
@Composable
fun CheckboxField(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    id: String? = null,
) {
    // No generated id here: this label WRAPS its control, which is a valid association on its own
    // — a `for` would be redundant, and an id nothing points at is noise.
    Label(attrs = { classes("f-check") }) {
        CheckboxInput(checked = checked) {
            id?.let { attr("id", it) }
            onChange { event -> onChange(event.value) }
        }
        Text(label)
    }
}

/** Enough room to read a paragraph of a synopsis without the box dominating the form. */
private const val TEXTAREA_ROWS = 6
