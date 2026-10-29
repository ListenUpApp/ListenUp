package com.calypsan.listenup.client.features.library.components

import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.library.BookStatusCounts
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_count_unit
import listenup.composeapp.generated.resources.library_filter_sort_trigger
import listenup.composeapp.generated.resources.library_filter_sort_trigger_a11y
import listenup.composeapp.generated.resources.library_ignore_articles_quoted
import listenup.composeapp.generated.resources.library_sort_by
import listenup.composeapp.generated.resources.library_sort_trigger
import listenup.composeapp.generated.resources.library_sort_trigger_a11y
import listenup.composeapp.generated.resources.library_status_filters_label
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.shape.CircleShape

/**
 * The Library's unified sort control — one Material 3 Expressive "sort card" that holds the count,
 * the sort category, the Ascending/Descending direction, and the "ignore leading articles" toggle
 * together.
 *
 * Layout: a tonal, capsule-shaped trigger that says the sort in words ("Title, A–Z", spec §5.1 c5),
 * after a muted "{count} {unit}" label when there is a count. Tapping the trigger opens a single
 * [DropdownMenu] that groups everything — category items (checked when selected), then both
 * directions named for the category, then (when [showArticleToggle]) the article toggle. Every label
 * comes from the string catalog (R12). Selecting a category dismisses the menu; choosing a direction
 * or toggling articles keeps it open so the effect is visible immediately. The whole control fades
 * with [visible] for the scroll affordance.
 *
 * Every Library section uses this one control, mirroring the unified iOS sort menu and web's Sort
 * dropdown.
 *
 * @param state Current sort state (category + direction).
 * @param categories Available sort categories for the active tab.
 * @param count Number of items under the active filter; null shows the trigger alone, leading the row.
 * @param unit Plural noun for [count] (e.g. "titles" / "series").
 * @param ignoreArticles Whether leading articles (A / An / The) are ignored in the text sort.
 * @param showArticleToggle Whether to offer the article toggle (only for the text category).
 * @param onCategorySelected Invoked when a new category is chosen.
 * @param onDirectionToggle Invoked when the other direction is chosen.
 * @param onToggleArticles Invoked when the article toggle is tapped.
 * @param visible Whether the card is shown (scroll fade).
 * @param modifier Optional modifier.
 * @param status Books only: the reading-state filter, which the trigger names first ("All · Title, A–Z") and the
 *   menu offers as a "Show" group above "Sort by". Null for Series, Authors and Narrators.
 * @param enabled False stills the trigger (while books are being picked: filtering would change the selection).
 */
@Suppress("LongParameterList")
@Composable
internal fun LibrarySortCard(
    state: SortState,
    categories: List<SortCategory>,
    count: Int?,
    unit: String,
    ignoreArticles: Boolean,
    showArticleToggle: Boolean,
    onCategorySelected: (SortCategory) -> Unit,
    onDirectionToggle: () -> Unit,
    onToggleArticles: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
    status: LibraryStatusChoice? = null,
    enabled: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenMargin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (count != null) {
                Text(
                    text = stringResource(Res.string.library_count_unit, count, unit),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }

            SortTrigger(
                state = state,
                categories = categories,
                ignoreArticles = ignoreArticles,
                showArticleToggle = showArticleToggle,
                onCategorySelected = onCategorySelected,
                onDirectionToggle = onDirectionToggle,
                onToggleArticles = onToggleArticles,
                status = status,
                enabled = enabled,
            )
        }
    }
}

/**
 * The Books view's reading-state filter, carried by its sort control (Simon, 2026-10-27): the status lives in the
 * Filter and sort menu as a "Show" group, as on iPhone, rather than a chip row.
 *
 * @property selected The active filter.
 * @property counts Whole-library counts, one per menu row.
 * @property onSelect Invoked with the chosen filter; the menu closes.
 */
internal data class LibraryStatusChoice(
    val selected: BookStatusFilter,
    val counts: BookStatusCounts,
    val onSelect: (BookStatusFilter) -> Unit,
)

/**
 * The tonal capsule trigger + the unified sort menu it opens. The Android tablet's second header band draws this one
 * trigger, rather than a second sort control. With [status] it is Books' Filter and sort: a filter glyph, the status
 * ahead of the sort in its words, and the "Show" group in its menu.
 */
@Suppress("LongParameterList")
@Composable
internal fun SortTrigger(
    state: SortState,
    categories: List<SortCategory>,
    ignoreArticles: Boolean,
    showArticleToggle: Boolean,
    onCategorySelected: (SortCategory) -> Unit,
    onDirectionToggle: () -> Unit,
    onToggleArticles: () -> Unit,
    status: LibraryStatusChoice? = null,
    enabled: Boolean = true,
) {
    val haptics = LocalHaptics.current
    var menuExpanded by remember { mutableStateOf(false) }
    val categoryLabel = state.category.catalogLabel()
    val directionLabel = state.category.catalogDirectionLabel(state.direction)
    val triggerText: String
    val triggerDescription: String
    if (status == null) {
        triggerText = stringResource(Res.string.library_sort_trigger, categoryLabel, directionLabel)
        triggerDescription = stringResource(Res.string.library_sort_trigger_a11y, categoryLabel, directionLabel)
    } else {
        val statusLabel = status.selected.label()
        triggerText = stringResource(Res.string.library_filter_sort_trigger, statusLabel, categoryLabel, directionLabel)
        triggerDescription =
            stringResource(Res.string.library_filter_sort_trigger_a11y, statusLabel, categoryLabel, directionLabel)
    }

    Box {
        Surface(
            onClick = {
                haptics.press()
                menuExpanded = true
            },
            enabled = enabled,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            // The name goes on the Surface and the words inside are cleared, not the Surface's own
            // semantics: clearing those would also clear its click action, and TalkBack activates a
            // control through that action — a double-tap would announce a button and do nothing.
            modifier =
                Modifier.semantics {
                    contentDescription = triggerDescription
                    role = Role.Button
                },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .clearAndSetSemantics {}
                        .height(42.dp)
                        .padding(start = 10.dp, end = 14.dp),
            ) {
                Icon(
                    imageVector = if (status == null) Icons.Default.SwapVert else Icons.Default.FilterList,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = triggerText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        SortMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            state = state,
            categories = categories,
            ignoreArticles = ignoreArticles,
            showArticleToggle = showArticleToggle,
            onCategorySelected = { category ->
                onCategorySelected(category)
                menuExpanded = false
            },
            onDirectionToggle = onDirectionToggle,
            onToggleArticles = onToggleArticles,
            status =
                status?.let { choice ->
                    choice.copy(
                        onSelect = { filter ->
                            choice.onSelect(filter)
                            menuExpanded = false
                        },
                    )
                },
        )
    }
}

/**
 * The single unified menu: with [status], a "Show" group of the four reading states (each with its count) and a
 * "Sort by" heading; then category options, direction, and (optionally) the article toggle.
 */
@Suppress("LongParameterList")
@Composable
private fun SortMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: SortState,
    categories: List<SortCategory>,
    ignoreArticles: Boolean,
    showArticleToggle: Boolean,
    onCategorySelected: (SortCategory) -> Unit,
    onDirectionToggle: () -> Unit,
    onToggleArticles: () -> Unit,
    status: LibraryStatusChoice?,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        if (status != null) {
            MenuGroupHeading(stringResource(Res.string.library_status_filters_label))
            BookStatusFilter.entries.forEach { filter ->
                CheckableMenuItem(
                    label = filter.label(),
                    checked = filter == status.selected,
                    onClick = { status.onSelect(filter) },
                    trailing = status.counts.countFor(filter).toString(),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MenuGroupHeading(stringResource(Res.string.library_sort_by))
        }
        categories.forEach { category ->
            CheckableMenuItem(
                label = category.catalogLabel(),
                checked = category == state.category,
                onClick = { onCategorySelected(category) },
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Both directions as a choice, each named for this category ("A–Z" / "Z–A", "Shortest" /
        // "Longest"); picking the current one does nothing. The menu stays open so the effect shows.
        listOf(SortDirection.ASCENDING, SortDirection.DESCENDING).forEach { direction ->
            CheckableMenuItem(
                label = state.category.catalogDirectionLabel(direction),
                checked = direction == state.direction,
                onClick = { if (direction != state.direction) onDirectionToggle() },
            )
        }

        if (showArticleToggle) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            CheckableMenuItem(
                label = stringResource(Res.string.library_ignore_articles_quoted),
                checked = ignoreArticles,
                onClick = onToggleArticles,
                isToggle = true,
            )
        }
    }
}

/** A group's label inside the menu (M3 menu group label): a heading for TalkBack, not a row it can activate. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MenuGroupHeading(text: String) {
    MenuDefaults.Label {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * A menu row with a leading [Check] when [checked] (and a matching spacer when not, so labels align).
 * The check is also carried in semantics, not by the glyph alone: a sort category is one choice of
 * several (a selected radio), while [isToggle] makes it an on/off checkbox (ignore articles). [trailing] is a quiet
 * count at the row's end (the Show group's).
 */
@Composable
private fun CheckableMenuItem(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    isToggle: Boolean = false,
    trailing: String? = null,
) {
    val haptics = LocalHaptics.current
    DropdownMenuItem(
        modifier =
            Modifier.semantics {
                if (isToggle) {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(checked)
                } else {
                    role = Role.RadioButton
                    selected = checked
                }
            },
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (checked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
        },
        onClick = {
            haptics.selectionTick()
            onClick()
        },
        trailingIcon =
            trailing?.let { count ->
                {
                    Text(
                        text = count,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        leadingIcon = {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Spacer(modifier = Modifier.size(18.dp))
            }
        },
    )
}
