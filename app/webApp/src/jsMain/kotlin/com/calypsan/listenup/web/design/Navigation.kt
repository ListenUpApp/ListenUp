package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * One tab of a [Tabs] strip.
 *
 * [count] is the badge a tab carries when it stands for a countable set — "Chapters 44",
 * "Files 3". It stays null for tabs that are a view rather than a collection.
 */
class TabItem(
    val key: String,
    val label: String,
    val icon: WebIcon? = null,
    val count: String? = null,
)

/**
 * The tab strip across a record.
 *
 * Tab identity is a [key] rather than an index because it goes in the URL — `?tab=chapters` is
 * part of the page contract, so that a link to a specific pane can be shared. Foundations is
 * explicit that nothing which changes what you see may hide in component state.
 *
 * A WAI-ARIA tablist, not a row of clickable `<div>`s (which is what it was, and why a keyboard
 * reader could see Chapters and never reach it): each tab is a `<button role="tab">`, only the
 * active one is a tab stop, and the arrows, Home and End move between them. Moving selects, as well
 * as focuses — switching a pane here is cheap and instant, which is when the pattern says automatic
 * activation is right.
 *
 * [idBase] names this strip's tabs and the [TabPanel] that follows it (`<idBase>-tab-<key>`,
 * `<idBase>-panel-<key>`), which is how a screen reader learns which tab a panel belongs to. Only
 * the active tab carries `aria-controls`: the page renders the one panel on show, and pointing the
 * others at ids that do not exist would be a reference to nothing.
 */
@Composable
fun Tabs(
    items: List<TabItem>,
    active: String,
    idBase: String,
    onSelect: ((String) -> Unit)? = null,
) {
    Div(attrs = {
        classes("tabs")
        attr("role", "tablist")
    }) {
        items.forEachIndexed { index, item ->
            val isActive = item.key == active
            Button(attrs = {
                classes("tab")
                if (isActive) classes("on")
                attr("type", BUTTON)
                attr("role", "tab")
                attr("id", tabId(idBase, item.key))
                attr("aria-selected", isActive.toString())
                if (isActive) attr("aria-controls", tabPanelId(idBase, item.key))
                tabIndex(if (isActive) 0 else -1)
                onSelect?.let { select ->
                    onClick { select(item.key) }
                    onKeyDown { event ->
                        rovingTarget(event.key, index, items.size, RovingAxis.Horizontal)?.let { next ->
                            event.preventDefault()
                            select(items[next].key)
                            event.currentTarget.focusSibling(":scope > [role=tab]", next)
                        }
                    }
                }
            }) {
                item.icon?.let { Icon(it, size = TAB_ICON_SIZE) }
                Text(item.label)
                item.count?.let { count ->
                    Span(attrs = { classes("ct") }) { Text(count) }
                }
            }
        }
    }
}

/**
 * The region a [Tabs] strip switches — `role="tabpanel"`, named by the tab that shows it.
 *
 * [idBase] and [key] must be the ones the strip was given, or the two stop pointing at each other.
 */
@Composable
fun TabPanel(
    idBase: String,
    key: String,
    content: @Composable () -> Unit,
) {
    Div(attrs = {
        // Keeps the page's own column rhythm: a pane that emits several panels would otherwise lose
        // the gap its parent spaces them with, now that they share one wrapper.
        classes("tabpanel")
        attr("role", "tabpanel")
        attr("id", tabPanelId(idBase, key))
        attr("aria-labelledby", tabId(idBase, key))
    }) { content() }
}

private fun tabId(
    idBase: String,
    key: String,
): String = "$idBase-tab-$key"

private fun tabPanelId(
    idBase: String,
    key: String,
): String = "$idBase-panel-$key"

/** One choice in a [SegmentedControl]. */
class SegmentItem(
    val key: String,
    val label: String,
    val icon: WebIcon? = null,
)

/**
 * A segmented control — a small, mutually-exclusive filter.
 *
 * Distinct from [Tabs] by weight, not mechanism: tabs switch what the page is showing, a segment
 * narrows what is already shown ("All 44 / Unheard 35 / Edited 2").
 *
 * A named group of `<button aria-pressed>`, the same shape the sort row uses, rather than the
 * clickable `<b>`s it was. [label] names what the group chooses between ("Show").
 */
@Composable
fun SegmentedControl(
    items: List<SegmentItem>,
    active: String,
    label: String,
    onSelect: ((String) -> Unit)? = null,
) {
    Div(attrs = {
        classes("seg")
        attr("role", "group")
        attr("aria-label", label)
    }) {
        items.forEach { item ->
            val isActive = item.key == active
            Button(attrs = {
                if (isActive) classes("on")
                attr("type", BUTTON)
                attr("aria-pressed", isActive.toString())
                onSelect?.let { select -> onClick { select(item.key) } }
            }) {
                item.icon?.let { Icon(it, size = SEGMENT_ICON_SIZE) }
                Text(item.label)
            }
        }
    }
}

/**
 * The three ways the Library can be browsed: the book grid itself, or the people behind it in
 * either credited role.
 *
 * A small enum rather than a raw role string, so a call site can never pass something a
 * [FacetRow] does not know how to render. "In progress" and "Series" are deliberately absent —
 * see [FacetRow].
 */
enum class LibraryFacet(
    val label: String,
) {
    Books("Books"),
    Series("Series"),
    Authors("Authors"),
    Narrators("Narrators"),
}

/**
 * The facet row that sits under a page title and does the navigating between the book grid, the
 * series list and the two Contributors lists — [com.calypsan.listenup.web.features.library.LibraryPage],
 * [com.calypsan.listenup.web.features.serieslist.SeriesListPage] and
 * [com.calypsan.listenup.web.features.contributors.ContributorsPage] all render it, so a reader
 * gets to the library's people from any side of that boundary the same way.
 *
 * Renders exactly the [LibraryFacet] entries, and still no "In progress" chip: that facet has no
 * destination behind it, and a chip whose only outcome is nothing is a lie. Series carried the same
 * note until it had a page to lead to — the bar is a real destination, not a place in a comp. This
 * replaces the
 * bespoke two-chip role toggle Contributors shipped with first: a facet row and a role toggle are
 * the same idiom wearing two names, and [SegmentedControl] already covers the "narrow what's
 * already shown" case a step down in weight from this one.
 */
@Composable
fun FacetRow(
    active: LibraryFacet,
    onSelect: (LibraryFacet) -> Unit,
) {
    Div(attrs = { classes("facet-row") }) {
        LibraryFacet.entries.forEach { facet ->
            FacetChip(facet = facet, active = active, onSelect = onSelect)
        }
    }
}

@Composable
private fun FacetChip(
    facet: LibraryFacet,
    active: LibraryFacet,
    onSelect: (LibraryFacet) -> Unit,
) {
    val isActive = facet == active
    Span(attrs = {
        classes("facet-chip")
        if (isActive) classes("is-active")
        attr("role", BUTTON)
        // A visual class alone doesn't tell a screen reader which facet is selected.
        attr("aria-pressed", isActive.toString())
        tabIndex(0)
        onClick { onSelect(facet) }
        onKeyDown { event ->
            if (event.key == "Enter" || event.key == " ") {
                event.preventDefault()
                onSelect(facet)
            }
        }
    }) { Text(facet.label) }
}

/**
 * A tag or filter chip.
 *
 * [onRemove] is what turns a tag into an applied-filter chip; supplying it adds the dismiss
 * affordance, so the same component covers "this book is Horror" and "you are filtering by
 * Horror".
 *
 * A non-null [onClick] makes the pill a real control, not just a styled label — it picks up the
 * same keyboard contract [FacetChip] does (focusable, `role=BUTTON`, Enter/Space activation),
 * so a chip that toggles something is reachable without a mouse.
 */
@Composable
fun Pill(
    label: String,
    selected: Boolean = false,
    icon: WebIcon? = null,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Span(attrs = {
        classes("pill")
        if (selected) classes("on")
        onClick?.let { click ->
            attr("role", BUTTON)
            tabIndex(0)
            onClick { click() }
            onKeyDown { event ->
                if (event.key == "Enter" || event.key == " ") {
                    event.preventDefault()
                    click()
                }
            }
        }
    }) {
        icon?.let { Icon(it, size = PILL_ICON_SIZE) }
        Text(label)
        if (onRemove != null) {
            Button(attrs = {
                classes("x")
                attr("type", BUTTON)
                attr("aria-label", "Remove $label")
                onClick { event ->
                    // Without this the click also reaches the pill itself, so removing a filter
                    // would toggle it on the way out.
                    event.stopPropagation()
                    onRemove()
                }
                // Same reason: Enter on this button must not also reach the pill's own key handler.
                onKeyDown { event -> event.stopPropagation() }
            }) {
                Icon(WebIcon.X, size = PILL_REMOVE_ICON_SIZE, strokeWidth = PILL_REMOVE_STROKE)
            }
        }
    }
}

private const val TAB_ICON_SIZE = 16

private const val SEGMENT_ICON_SIZE = 15

private const val PILL_ICON_SIZE = 13

private const val PILL_REMOVE_ICON_SIZE = 12

private const val PILL_REMOVE_STROKE = 2.2

/** Both a `type` and a `role` value here; one spelling for all of them. */
private const val BUTTON = "button"
