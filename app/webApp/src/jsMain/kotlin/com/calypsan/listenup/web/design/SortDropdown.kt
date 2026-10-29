package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState

/**
 * The Library's one sort control (board `Library-WebDesktop-Sort`): a dropdown whose trigger says the
 * sort in words ("Sort: Title, A–Z"), holding the categories, both directions and — for a title or
 * name sort — Ignore “A”, “An”, “The”, which used to live in Settings.
 *
 * The same control on all four Library pages (Books, Series, Authors, Narrators), so a reader learns
 * it once. Every choice rides a shared ViewModel event; the browser stores no sort of its own.
 *
 * [ignoreArticles] null means the toggle is not offered for this sort. Choosing a category closes the
 * menu; a direction or the checkbox keep it open, so the reader sees the list re-order beneath it.
 *
 * [status] is Books' reading-state filter (Simon, 2026-10-27): with it the control is Filter and sort, its trigger
 * "All · Title, A–Z" behind a filter glyph, and its menu opens on a "Show" group with each state's count, above
 * "Sort by", as on iPhone. Series, Authors and Narrators pass none. [enabled] false stills the trigger.
 */
@Composable
@Suppress("LongParameterList")
internal fun SortDropdown(
    noun: String,
    categories: List<SortCategory>,
    state: SortState,
    onCategory: (SortCategory) -> Unit,
    onToggleDirection: () -> Unit,
    ignoreArticles: Boolean?,
    onToggleIgnoreArticles: () -> Unit,
    status: SortStatus? = null,
    enabled: Boolean = true,
) {
    val sortWords = "${state.category.webLabel()}, ${state.category.webDirectionLabel(state.direction).asTriggerText()}"
    val shown = status?.rows?.firstOrNull { it.isSelected }?.label
    val summary = if (shown == null) "Sort: $sortWords" else "$shown · $sortWords"
    val label = if (shown == null) "$summary. Sort $noun" else "Filter and sort: $shown, $sortWords"
    val items =
        buildList {
            status?.rows?.forEach { row ->
                add(
                    MenuAction(
                        label = row.label,
                        icon = null,
                        onSelect = { if (!row.isSelected) row.onSelect() },
                        checked = row.isSelected,
                        group = SHOW_GROUP,
                        detail = row.count,
                    ),
                )
            }
            categories.forEach { category ->
                add(
                    MenuAction(
                        label = category.webLabel(),
                        icon = null,
                        onSelect = { if (category != state.category) onCategory(category) },
                        checked = category == state.category,
                        group = "Sort by",
                    ),
                )
            }
            listOf(SortDirection.ASCENDING, SortDirection.DESCENDING).forEach { direction ->
                add(
                    MenuAction(
                        label = state.category.webDirectionLabel(direction),
                        icon = null,
                        onSelect = { if (direction != state.direction) onToggleDirection() },
                        checked = direction == state.direction,
                        group = "Direction",
                        keepsOpen = true,
                    ),
                )
            }
            if (ignoreArticles != null) {
                add(
                    MenuAction(
                        label = IGNORE_ARTICLES_LABEL,
                        icon = null,
                        onSelect = onToggleIgnoreArticles,
                        checked = ignoreArticles,
                        checkbox = true,
                        keepsOpen = true,
                    ),
                )
            }
        }
    PopupMenu(
        items = items,
        // Starts with the words on the trigger (WCAG 2.5.3), so a voice command can say them.
        label = label,
        icon = if (status == null) WebIcon.ChevronDown else WebIcon.FilterLines,
        triggerClasses = arrayOf(*buttonClasses(ButtonKind.Secondary, ButtonSize.Md), "lib-sort-trigger"),
        enabled = enabled,
        triggerText = summary,
    )
}

/**
 * Books' reading-state filter, carried by its Sort control, already in words: the design layer knows no feature.
 *
 * @property rows One per reading state, in order; exactly one [SortStatusRow.isSelected].
 */
internal data class SortStatus(
    val rows: List<SortStatusRow>,
)

/**
 * One reading state in the Show group: "In progress" with "4".
 *
 * @property label The state's word, which the trigger leads with when it is selected.
 * @property count The state's count, grouped ("1,286").
 * @property isSelected Whether it is the filter in force.
 * @property onSelect Chooses it.
 */
internal data class SortStatusRow(
    val label: String,
    val count: String,
    val isSelected: Boolean,
    val onSelect: () -> Unit,
)

private const val SHOW_GROUP = "Show"

/** The trigger's compact direction: "A → Z" reads "A–Z" there, as on the board. */
private fun String.asTriggerText(): String = replace(" → ", "–")

/** The category's name. Web copy is inline English, as on every web page; it matches the catalogs. */
internal fun SortCategory.webLabel(): String =
    when (this) {
        SortCategory.TITLE -> "Title"
        SortCategory.AUTHOR -> "Author"
        SortCategory.SERIES -> "Series"
        SortCategory.ADDED -> "Recently added"
        SortCategory.DURATION -> "Length"
        SortCategory.YEAR -> "Year published"
        SortCategory.RATING -> "Rating"
        SortCategory.LISTENER_RATING -> "Your listeners' rating"
        SortCategory.NAME -> "Name"
        SortCategory.BOOK_COUNT -> "Number of books"
    }

/** How [direction] reads for this category: "A → Z", "Longest", "Newest first". */
internal fun SortCategory.webDirectionLabel(direction: SortDirection): String {
    val ascending = direction == SortDirection.ASCENDING
    return when (this) {
        SortCategory.TITLE, SortCategory.AUTHOR, SortCategory.NAME -> if (ascending) "A → Z" else "Z → A"
        SortCategory.DURATION -> if (ascending) "Shortest" else "Longest"
        SortCategory.YEAR -> if (ascending) "Oldest" else "Newest"
        SortCategory.BOOK_COUNT -> if (ascending) "Fewest" else "Most"
        SortCategory.ADDED -> if (ascending) "Oldest first" else "Newest first"
        SortCategory.RATING, SortCategory.LISTENER_RATING -> if (ascending) "Lowest" else "Highest"
        SortCategory.SERIES -> if (ascending) "1 → N" else "N → 1"
    }
}

private const val IGNORE_ARTICLES_LABEL = "Ignore “A”, “An”, “The”"
