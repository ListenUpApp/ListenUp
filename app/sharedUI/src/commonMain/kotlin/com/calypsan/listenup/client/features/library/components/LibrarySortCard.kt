package com.calypsan.listenup.client.features.library.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_count_unit
import listenup.composeapp.generated.resources.library_ignore_articles
import listenup.composeapp.generated.resources.library_sort_ascending
import listenup.composeapp.generated.resources.library_sort_descending
import listenup.composeapp.generated.resources.library_sort_options
import org.jetbrains.compose.resources.stringResource

/**
 * The Library's unified sort control — one Material 3 Expressive "sort card" that holds the count,
 * the sort category, the Ascending/Descending direction, and the "ignore leading articles" toggle
 * together.
 *
 * Layout: a muted "{count} {unit}" label on the left and a tonal, capsule-shaped trigger on the
 * right showing the current category plus a direction arrow. Tapping the trigger opens a single
 * [DropdownMenu] that groups everything — category items (checked when selected), then the
 * direction toggle, then (when [showArticleToggle]) the article toggle. Selecting a category
 * dismisses the menu; toggling direction or articles keeps it open so the effect is visible
 * immediately. The whole control fades with [visible] for the scroll affordance.
 *
 * This replaces the old two-control split (`SortSplitButton` + `LibrarySortBar`) with one cohesive
 * surface, mirroring the unified iOS sort menu.
 *
 * @param state Current sort state (category + direction).
 * @param categories Available sort categories for the active tab.
 * @param count Number of items under the active filter.
 * @param unit Plural noun for [count] (e.g. "titles" / "series").
 * @param ignoreArticles Whether leading articles (A / An / The) are ignored in the text sort.
 * @param showArticleToggle Whether to offer the article toggle (only for the text category).
 * @param onCategorySelected Invoked when a new category is chosen.
 * @param onDirectionToggle Invoked when the direction row is tapped.
 * @param onToggleArticles Invoked when the article toggle is tapped.
 * @param visible Whether the card is shown (scroll fade).
 * @param modifier Optional modifier.
 */
@Suppress("LongParameterList")
@Composable
fun LibrarySortCard(
    state: SortState,
    categories: List<SortCategory>,
    count: Int,
    unit: String,
    ignoreArticles: Boolean,
    showArticleToggle: Boolean,
    onCategorySelected: (SortCategory) -> Unit,
    onDirectionToggle: () -> Unit,
    onToggleArticles: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
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
            Text(
                text = stringResource(Res.string.library_count_unit, count, unit),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )

            SortTrigger(
                state = state,
                categories = categories,
                ignoreArticles = ignoreArticles,
                showArticleToggle = showArticleToggle,
                onCategorySelected = onCategorySelected,
                onDirectionToggle = onDirectionToggle,
                onToggleArticles = onToggleArticles,
            )
        }
    }
}

/**
 * The tonal capsule trigger + the unified sort menu it opens.
 */
@Suppress("LongParameterList")
@Composable
private fun SortTrigger(
    state: SortState,
    categories: List<SortCategory>,
    ignoreArticles: Boolean,
    showArticleToggle: Boolean,
    onCategorySelected: (SortCategory) -> Unit,
    onDirectionToggle: () -> Unit,
    onToggleArticles: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val triggerLabel = stringResource(Res.string.library_sort_options)

    Box {
        Surface(
            onClick = { menuExpanded = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier =
                Modifier.clearAndSetSemantics {
                    contentDescription = "$triggerLabel: ${state.category.label}, ${state.directionLabel}"
                },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .height(42.dp)
                        .padding(start = 16.dp, end = 12.dp),
            ) {
                Icon(
                    imageVector = state.direction.arrowIcon(),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = state.category.label,
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
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
        )
    }
}

/**
 * The single unified menu: category options, then direction, then (optionally) the article toggle.
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
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        categories.forEach { category ->
            CheckableMenuItem(
                label = category.label,
                checked = category == state.category,
                onClick = { onCategorySelected(category) },
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val directionLabel =
            stringResource(
                if (state.direction == SortDirection.ASCENDING) {
                    Res.string.library_sort_ascending
                } else {
                    Res.string.library_sort_descending
                },
            )
        DropdownMenuItem(
            text = {
                Text(
                    text = directionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            onClick = onDirectionToggle,
            leadingIcon = {
                Icon(
                    imageVector = state.direction.arrowIcon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            },
        )

        if (showArticleToggle) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            CheckableMenuItem(
                label = stringResource(Res.string.library_ignore_articles),
                checked = ignoreArticles,
                onClick = onToggleArticles,
            )
        }
    }
}

/**
 * A menu row with a leading [Check] when [checked] (and a matching spacer when not, so labels align).
 */
@Composable
private fun CheckableMenuItem(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
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
        onClick = onClick,
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

/** Up arrow for ascending, down arrow for descending. */
private fun SortDirection.arrowIcon() =
    when (this) {
        SortDirection.ASCENDING -> Icons.Default.ArrowUpward
        SortDirection.DESCENDING -> Icons.Default.ArrowDownward
    }
