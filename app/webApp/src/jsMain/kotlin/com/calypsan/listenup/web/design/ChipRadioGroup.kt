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
 *
 * [same] decides which option [selected] is, for values compared with a tolerance (a playback speed); when none is, no
 * radio is checked and the first keeps the tab stop, so the group is still reachable. [optionClass] is added to every
 * option, for a group drawn its own way (the player's speed presets and boost levels).
 */
@Composable
fun <T> ChipRadioGroup(
    label: String,
    options: List<ChipOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    styleClass: String = "chip-radio",
    same: (T, T) -> Boolean = { a, b -> a == b },
    optionClass: String? = null,
) {
    val checkedIndex = options.indexOfFirst { same(it.value, selected) }
    val tabStop = if (checkedIndex >= 0) checkedIndex else 0
    Div(attrs = {
        classes(styleClass)
        attr("role", "radiogroup")
        attr("aria-label", label)
    }) {
        options.forEachIndexed { index, option ->
            val isOn = index == checkedIndex
            Button(attrs = {
                classes("chip-radio-opt")
                optionClass?.let { classes(it) }
                if (isOn) classes("is-on")
                attr("type", "button")
                attr("role", "radio")
                attr("aria-checked", isOn.toString())
                tabIndex(if (index == tabStop) 0 else -1)
                onClick { if (!isOn) onSelect(option.value) }
                onKeyDown { event ->
                    rovingIndex(event.key, index, options.size)?.let { next ->
                        event.preventDefault()
                        if (next != index) onSelect(options[next].value)
                        event.currentTarget.focusSibling(":scope > [role=radio]", next)
                    }
                }
            }) { Text(option.label) }
        }
    }
}

/** Where an arrow key moves a radio group's selection from [index]: either axis, wrapping; null for other keys. */
private fun rovingIndex(
    key: String,
    index: Int,
    size: Int,
): Int? =
    rovingTarget(key, index, size, RovingAxis.Horizontal)
        ?: rovingTarget(key, index, size, RovingAxis.Vertical)
