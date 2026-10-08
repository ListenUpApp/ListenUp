package com.calypsan.listenup.client.features.shelf

import androidx.compose.ui.semantics.CustomAccessibilityAction
import com.calypsan.listenup.client.presentation.shelf.reorderedBy

/**
 * The non-drag way to reorder a shelf: "Move earlier" / "Move later" accessibility actions for the
 * book at [index], each landing it one place over through the same [reorderedBy] the drag uses.
 * TalkBack and Switch Access users cannot drag, so without these a shelf's order is theirs to look
 * at but not to change. An action that would move past either end is simply not offered.
 *
 * @param items The shelf in its current (manual) order.
 * @param index The position of the book these actions move.
 * @param moveEarlierLabel Localized label for moving one place towards the start.
 * @param moveLaterLabel Localized label for moving one place towards the end.
 * @param onReorder Receives the whole reordered shelf.
 */
internal fun <T> shelfReorderActions(
    items: List<T>,
    index: Int,
    moveEarlierLabel: String,
    moveLaterLabel: String,
    onReorder: (List<T>) -> Unit,
): List<CustomAccessibilityAction> =
    buildList {
        if (index > 0) {
            add(
                moveAction(
                    label = moveEarlierLabel,
                    items = items,
                    from = index,
                    to = index - 1,
                    onReorder = onReorder,
                ),
            )
        }
        if (index < items.lastIndex) {
            add(
                moveAction(
                    label = moveLaterLabel,
                    items = items,
                    from = index,
                    to = index + 1,
                    onReorder = onReorder,
                ),
            )
        }
    }

private fun <T> moveAction(
    label: String,
    items: List<T>,
    from: Int,
    to: Int,
    onReorder: (List<T>) -> Unit,
): CustomAccessibilityAction =
    CustomAccessibilityAction(label) {
        onReorder(reorderedBy(items, from, to))
        true
    }
