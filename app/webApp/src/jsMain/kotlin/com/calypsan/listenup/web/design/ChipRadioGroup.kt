package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/**
 * One option of a [ChipRadioGroup].
 *
 * @property value What choosing it selects.
 * @property label The text it shows, which is also its accessible name.
 */
data class ChipOption<T>(
    val value: T,
    val label: String,
)

/**
 * A single choice drawn as chips (`chip-radio`) or a segmented track (`seg-radio`): `role=radiogroup` of
 * `role=radio` with `aria-checked` written as "true"/"false", one tab stop (the checked one), and the
 * arrow keys moving the selection with the focus, wrapping at the ends (WAI-ARIA radio group; spec §8.2).
 */
@Composable
fun <T> ChipRadioGroup(
    label: String,
    options: List<ChipOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    styleClass: String = "chip-radio",
) {
    Div(attrs = {
        classes(styleClass)
        attr("role", "radiogroup")
        attr("aria-label", label)
    }) {
        options.forEachIndexed { index, option ->
            val isOn = option.value == selected
            Button(attrs = {
                classes("chip-radio-opt")
                if (isOn) classes("is-on")
                attr("type", "button")
                attr("role", "radio")
                attr("aria-checked", isOn.toString())
                tabIndex(if (isOn) 0 else -1)
                onClick { if (!isOn) onSelect(option.value) }
                onKeyDown { event ->
                    val next =
                        rovingTarget(event.key, index, options.size, RovingAxis.Horizontal)
                            ?: rovingTarget(event.key, index, options.size, RovingAxis.Vertical)
                    if (next != null) {
                        event.preventDefault()
                        if (next != index) onSelect(options[next].value)
                        event.currentTarget.focusSibling(":scope > [role=radio]", next)
                    }
                }
            }) { Text(option.label) }
        }
    }
}
