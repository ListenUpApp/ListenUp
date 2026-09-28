package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/**
 * A list's sort row: what it is sorted by, and which way.
 *
 * One component for the Library, Series and Contributors lists, which each carried a private copy
 * made of `<div onClick>`s — unreachable by keyboard — with the direction shown as a bare arrow
 * character that a screen reader read out as punctuation, if it read it at all.
 *
 * The options are `<button aria-pressed>` in a group named "Sort by", so the chosen one is announced
 * as pressed. The direction is its own button, named by the order the list is in now ("Sort
 * ascending") and drawn as an arrow icon, which is hidden behind that name.
 *
 * Generic over the option type so each page keeps its own sort enum and passes [labelOf], rather than
 * this component learning about every list it serves.
 */
@Composable
fun <T> SortControl(
    options: List<T>,
    active: T,
    labelOf: (T) -> String,
    ascending: Boolean,
    onSelect: (T) -> Unit,
    onToggleDirection: () -> Unit,
) {
    Div(attrs = {
        classes("lib-sort")
        attr("role", "group")
        attr("aria-label", "Sort by")
    }) {
        options.forEach { option ->
            val isActive = option == active
            Button(attrs = {
                classes("lib-sort-option")
                if (isActive) classes("is-active")
                attr("type", "button")
                attr("aria-pressed", isActive.toString())
                onClick { onSelect(option) }
            }) { Text(labelOf(option)) }
        }
        val directionName = if (ascending) "Sort ascending" else "Sort descending"
        Button(attrs = {
            classes("lib-sort-direction")
            attr("type", "button")
            attr("aria-label", directionName)
            attr("title", directionName)
            onClick { onToggleDirection() }
        }) {
            Icon(if (ascending) WebIcon.ArrowUp else WebIcon.ArrowDown, size = DIRECTION_ICON_SIZE)
        }
    }
}

private const val DIRECTION_ICON_SIZE = 15
