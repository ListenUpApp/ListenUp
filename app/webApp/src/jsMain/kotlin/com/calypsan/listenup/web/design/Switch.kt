package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLInputElement

/**
 * An on/off switch with its label above it.
 *
 * A real `<input type="checkbox">` wearing `role="switch"`, not a styled `<div>`. That is what
 * makes it reachable by Tab, togglable by Space, announced as "switch, on", and included when a
 * form is read out — none of which a div with a click handler gets, and all of which it would
 * reimplement worse. The track and thumb are drawn from the input's own `::before`/sibling in CSS,
 * so the accessible control and the visible one are the same element rather than two that can
 * drift.
 *
 * Distinct from [CheckboxField], which is the right control for "include this" in a form. A switch
 * is for a setting that takes effect the moment it moves — which is exactly what the notification
 * preferences do, since each flick is an immediate write.
 *
 * [enabled] false renders a genuinely `disabled` input rather than an inert-looking one: a channel
 * the server has declared ineligible must be unreachable by keyboard too, and must say so when
 * read aloud.
 *
 * [describedBy] is the id of the sentence beside the switch that says what it does, so a screen reader
 * reads it with the switch rather than as a stray line somewhere near it.
 *
 * Fully controlled (#1541): the box only ever shows [checked]. A change asks [onChange] and snaps
 * back; the state moving is what moves the box. A switch that asks for confirmation stays on behind
 * the dialog, and one whose save is refused reads as it was.
 */
@Composable
fun SwitchField(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    describedBy: String? = null,
) {
    Label(attrs = {
        classes("sw")
        if (!enabled) classes("off")
    }) {
        Span(attrs = { classes("sw-lb") }) { Text(label) }
        Input(type = InputType.Checkbox) {
            classes("sw-in")
            attr("role", "switch")
            if (checked) attr("checked", "")
            if (!enabled) attr("disabled", "")
            describedBy?.let { attr("aria-describedby", it) }
            // The attribute stops governing the box once it has been clicked, so the property is set
            // from state on every change of it.
            prop({ input: HTMLInputElement, on: Boolean -> input.checked = on }, checked)
            onChange { event ->
                val wanted = event.value
                // A click never moves the box by itself: the state does, so a switch that asks first
                // stays where it was.
                event.target.checked = checked
                if (enabled) onChange(wanted)
            }
        }
        // Purely the visual track; the input above is the control. `aria-hidden` so a screen
        // reader is not offered a second, meaningless thing to interact with.
        Span(attrs = {
            classes("sw-track")
            attr("aria-hidden", "true")
        }) { Span(attrs = { classes("sw-thumb") }) }
    }
}
