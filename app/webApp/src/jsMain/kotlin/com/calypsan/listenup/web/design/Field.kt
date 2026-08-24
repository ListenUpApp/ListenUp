package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
) {
    val fieldId = rememberFieldId(id)
    Div(attrs = { classes("f-wrap") }) {
        Label(attrs = {
            classes("f-label")
            attr("for", fieldId)
        }) { Text(label) }
        Div(attrs = {
            classes("f-box")
            if (error) classes("err")
        }) {
            leading?.let { Icon(it, size = FIELD_ICON_SIZE, attrs = { classes("f-ico") }) }
            Input(type = type) {
                classes("f-input")
                value(value)
                if (placeholder.isNotEmpty()) attr("placeholder", placeholder)
                attr("id", fieldId)
                autocomplete?.let { attr("autocomplete", it) }
                onInput { event -> onInput(event.value) }
            }
        }
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
) {
    val fieldId = rememberFieldId(id)
    var revealed by remember { mutableStateOf(false) }

    Div(attrs = { classes("f-wrap") }) {
        Label(attrs = {
            classes("f-label")
            attr("for", fieldId)
        }) { Text(label) }
        Div(attrs = {
            classes("f-box")
            if (error) classes("err")
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
    }
}

private const val FIELD_ICON_SIZE = 19
