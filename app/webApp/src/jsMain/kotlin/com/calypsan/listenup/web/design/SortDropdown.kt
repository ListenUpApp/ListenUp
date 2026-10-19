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
) {
    val summary = "Sort: ${state.category.webLabel()}, ${state.category.webDirectionLabel(
        state.direction,
    ).asTriggerText()}"
    val items =
        buildList {
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
        label = "$summary. Sort $noun",
        icon = WebIcon.ChevronDown,
        triggerClasses = arrayOf(*buttonClasses(ButtonKind.Secondary, ButtonSize.Md), "lib-sort-trigger"),
        triggerText = summary,
    )
}

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
