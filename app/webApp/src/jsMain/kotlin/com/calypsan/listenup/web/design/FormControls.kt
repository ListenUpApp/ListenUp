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
 * Carries its own chevron. The sheet sets `appearance:none` to get the field shape it wants, which
 * also removes the arrow the browser draws — so without one here a select is pixel-identical to a
 * text input, and reads as a box that inexplicably refuses to accept typing.
 *
 * [emptyLabel] names the unset case and defaults to absent, because most pickers do not have one:
 * a theme is always one of three, and offering a fourth "—" invites a reader to choose a state the
 * screen cannot be in. Metadata is where unset is real ("no language recorded"), so those call
 * sites name it. A null [value] still renders a placeholder whatever the caller passed — better a
 * bare dash than a select silently showing its first option as though it were the stored one.
 */
@Composable
fun SelectField(
    label: String,
    value: String?,
    options: List<SelectOption>,
    onSelect: (String?) -> Unit,
    emptyLabel: String? = null,
    id: String? = null,
) {
    val fieldId = rememberFieldId(id)
    Div(attrs = { classes("f-wrap") }) {
        Label(attrs = {
            classes("f-label")
            attr("for", fieldId)
        }) { Text(label) }
        Div(attrs = { classes("f-box", "f-box-select") }) {
            Select(attrs = {
                classes("f-input", "f-select")
                attr("id", fieldId)
                onChange { event -> onSelect(event.value?.takeIf { it.isNotEmpty() }) }
            }) {
                if (emptyLabel != null || value == null) {
                    Option(value = "", attrs = { if (value == null) selected() }) {
                        Text(emptyLabel ?: "—")
                    }
                }
                options.forEach { option ->
                    Option(value = option.value, attrs = { if (option.value == value) selected() }) {
                        Text(option.label)
                    }
                }
            }
            Icon(WebIcon.ChevronDown, size = SELECT_CARET_SIZE, attrs = { classes("f-caret") })
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

/** A shade under the 19px field icons: a disclosure hint, not a thing to look at. */
private const val SELECT_CARET_SIZE = 17
