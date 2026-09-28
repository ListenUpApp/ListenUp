package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Text

/**
 * The single text field of the web kit.
 *
 * Controlled: [value] is the truth and [onInput] is the only way it changes, so a form's state
 * lives in one place rather than being split between Kotlin and the DOM. The shared auth
 * ViewModels take credentials as *submit* arguments rather than per-keystroke state, which is why
 * the caller — not a ViewModel — owns this value.
 *
 * [error] is a class rather than an inline style because the sheet owns colour: `.f-box.err` has
 * to cooperate with `:focus-within` and with dark mode, and an inline style would beat both.
 *
 * [errorText] is the words of a problem with *this* field. It renders under the box with a stable id
 * (`<fieldId>-err`), and the input points at it with `aria-describedby` and says `aria-invalid` — so
 * a screen reader reads the problem with the field, not as an orphan paragraph somewhere below the
 * form. It implies [error]; [error] alone stays for a field that is wrong without its own words (a
 * form-level failure that names the field elsewhere).
 *
 * [enabled] false is for a value the server will not let change — a system collection's name, say.
 * Rendering the field editable and refusing the save afterwards teaches the reader that the app
 * lies about what it will accept.
 */
@Composable
fun Field(
    label: String,
    value: String,
    onInput: (String) -> Unit,
    leading: WebIcon? = null,
    placeholder: String = "",
    type: InputType<String> = InputType.Text,
    error: Boolean = false,
    id: String? = null,
    autocomplete: String? = null,
    enabled: Boolean = true,
    errorText: String? = null,
) {
    val fieldId = rememberFieldId(id)
    val invalid = error || errorText != null
    Div(attrs = { classes("f-wrap") }) {
        Label(attrs = {
            classes("f-label")
            attr("for", fieldId)
        }) { Text(label) }
        Div(attrs = {
            classes("f-box")
            if (invalid) classes("err")
            if (!enabled) classes("off")
        }) {
            leading?.let { Icon(it, size = FIELD_ICON_SIZE, attrs = { classes("f-ico") }) }
            Input(type = type) {
                classes("f-input")
                value(value)
                if (placeholder.isNotEmpty()) attr("placeholder", placeholder)
                attr("id", fieldId)
                autocomplete?.let { attr("autocomplete", it) }
                // A genuinely `disabled` input, not one that merely looks inert: a value the
                // server will refuse to change must be unreachable by keyboard too, and must say
                // so when read aloud. Same rule [SwitchField] follows for an ineligible channel.
                if (!enabled) attr("disabled", "")
                describeError(fieldId, invalid, errorText)
                onInput { event -> if (enabled) onInput(event.value) }
            }
        }
        errorText?.let { FieldError(fieldId, it) }
    }
}

/**
 * A [Field] that starts masked and can be revealed.
 *
 * Reveal is local state, not a parameter: whether the user is currently peeking at their password
 * is nobody else's business, and hoisting it would put a transient UI affordance into form state
 * that gets submitted.
 */
@Composable
fun PasswordField(
    label: String,
    value: String,
    onInput: (String) -> Unit,
    error: Boolean = false,
    id: String? = null,
    autocomplete: String? = null,
    errorText: String? = null,
) {
    val fieldId = rememberFieldId(id)
    val invalid = error || errorText != null
    var revealed by remember { mutableStateOf(false) }

    Div(attrs = { classes("f-wrap") }) {
        Label(attrs = {
            classes("f-label")
            attr("for", fieldId)
        }) { Text(label) }
        Div(attrs = {
            classes("f-box")
            if (invalid) classes("err")
        }) {
            Icon(WebIcon.Lock, size = FIELD_ICON_SIZE, attrs = { classes("f-ico") })
            Input(type = if (revealed) InputType.Text else InputType.Password) {
                classes("f-input")
                value(value)
                attr("id", fieldId)
                // Without this a password manager has no idea whether to offer the saved password
                // or propose a new one — and will often offer neither. `current-password` on sign
                // in, `new-password` wherever an account is being created.
                autocomplete?.let { attr("autocomplete", it) }
                describeError(fieldId, invalid, errorText)
                onInput { event -> onInput(event.value) }
            }
            Button(attrs = {
                classes("f-eye")
                attr("type", "button")
                attr("title", if (revealed) "Hide password" else "Show password")
                onClick { revealed = !revealed }
            }) {
                Icon(if (revealed) WebIcon.EyeOff else WebIcon.Eye, size = FIELD_ICON_SIZE)
            }
        }
        errorText?.let { FieldError(fieldId, it) }
    }
}

/** The id of the message a field's `aria-describedby` points at. Stable, so specs can address it. */
internal fun fieldErrorId(fieldId: String): String = "$fieldId-err"

private fun AttrsScope<*>.describeError(
    fieldId: String,
    invalid: Boolean,
    errorText: String?,
) {
    if (invalid) attr("aria-invalid", "true")
    if (errorText != null) attr("aria-describedby", fieldErrorId(fieldId))
}

@Composable
private fun FieldError(
    fieldId: String,
    message: String,
) {
    Div(attrs = {
        classes("f-err")
        id(fieldErrorId(fieldId))
    }) { Text(message) }
}

private const val FIELD_ICON_SIZE = 19
