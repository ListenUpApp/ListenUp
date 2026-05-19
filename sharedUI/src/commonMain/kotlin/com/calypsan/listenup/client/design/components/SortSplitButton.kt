@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortState
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_select_category
import listenup.composeapp.generated.resources.common_toggle_direction

/**
 * Split button for sort control.
 *
 * Two-part control:
 * - Leading: Shows current category, tap opens category dropdown
 * - Trailing: Shows current direction, tap toggles direction
 *
 * This design separates "what to sort by" from "in what order",
 * making direction toggling a single-tap operation.
 *
 * @param state Current sort state (category + direction)
 * @param categories Available categories for this tab
 * @param onCategorySelected Called when user selects a new category
 * @param onDirectionToggle Called when user taps to toggle direction
 * @param visible Whether the button should be visible (for scroll fade)
 * @param modifier Optional modifier
 */
@Composable
fun SortSplitButton(
    state: SortState,
    categories: List<SortCategory>,
    onCategorySelected: (SortCategory) -> Unit,
    onDirectionToggle: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
                shadowElevation = 2.dp,
                modifier = Modifier.clip(RoundedCornerShape(50)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(46.dp),
                ) {
                    // Leading: Category selector
                    Surface(
                        onClick = { categoryMenuExpanded = true },
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0f),
                        modifier = Modifier.height(46.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp, end = 10.dp),
                        ) {
                            Text(
                                text = state.category.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(Res.string.common_select_category),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Divider
                    VerticalDivider(
                        modifier =
                            Modifier
                                .height(26.dp)
                                .padding(vertical = 2.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    // Trailing: Direction toggle
                    Surface(
                        onClick = onDirectionToggle,
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0f),
                        modifier = Modifier.height(46.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 10.dp, end = 16.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapVert,
                                contentDescription = stringResource(Res.string.common_toggle_direction),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = state.directionLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // Category dropdown menu
            CategoryDropdownMenu(
                expanded = categoryMenuExpanded,
                onDismiss = { categoryMenuExpanded = false },
                currentCategory = state.category,
                categories = categories,
                onCategorySelected = { category ->
                    onCategorySelected(category)
                    categoryMenuExpanded = false
                },
            )
        }
    }
}

/**
 * Dropdown menu for selecting sort category.
 */
@Composable
private fun CategoryDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    currentCategory: SortCategory,
    categories: List<SortCategory>,
    onCategorySelected: (SortCategory) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        categories.forEach { category ->
            val isSelected = category == currentCategory

            DropdownMenuItem(
                text = {
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                },
                onClick = { onCategorySelected(category) },
                leadingIcon =
                    if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        {
                            Spacer(modifier = Modifier.size(18.dp))
                        }
                    },
            )
        }
    }
}
