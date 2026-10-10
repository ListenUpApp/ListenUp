package com.calypsan.listenup.client.navigation

import androidx.navigation3.runtime.NavKey

/**
 * Opens [destination] from the screen [from], first closing anything stacked above [from].
 *
 * A screen that can be a list pane (see [ListDetailScene]) navigates through this. When it is the
 * list pane, the detail beside it is above it on the stack: opening another book *replaces* that
 * book rather than stacking a second one for Back to walk through, and opening anything else leaves
 * from the list, as the user did. When [from] is the top of the stack — every compact window, and a
 * wide one showing the list alone — nothing is above it, and this is the plain push it replaces.
 */
internal fun MutableList<NavKey>.navigateFrom(
    from: NavKey,
    destination: NavKey,
) {
    val index = lastIndexOf(from)
    if (index >= 0) subList(index + 1, size).clear()
    add(destination)
}

/**
 * Lands [targets] for a navigation that came from outside the app — a push tap, a shared link, a
 * launcher shortcut.
 *
 * Normally the outside navigation starts from [root], clearing whatever was open so the target is
 * one Back from home. But an entry removed from the stack loses its view model, and with it an
 * upload in flight or an edit not yet saved: when [holdsUnsavedWork], nothing is removed and the
 * targets land on top of what is open, so Back returns to the work with it intact.
 */
internal fun MutableList<NavKey>.openFromOutside(
    root: NavKey,
    targets: List<NavKey>,
    holdsUnsavedWork: Boolean,
) {
    if (!holdsUnsavedWork && lastOrNull() != root) {
        clear()
        add(root)
    }
    addAll(targets)
}

/**
 * Back from the screen [screen]: removes it and anything stacked above it.
 *
 * For a list pane that is the detail beside it too — the list's own back arrow leaves the list,
 * rather than quietly closing the book next to it. When [screen] is the top of the stack this is the
 * plain pop it replaces, and so is the fallback for a screen that has already left the stack.
 */
internal fun MutableList<NavKey>.popFrom(screen: NavKey) {
    val index = lastIndexOf(screen)
    if (index >= 0) subList(index, size).clear() else removeAt(lastIndex)
}
