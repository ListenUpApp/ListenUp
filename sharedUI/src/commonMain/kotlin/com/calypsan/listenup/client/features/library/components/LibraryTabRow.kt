package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.calypsan.listenup.client.features.library.LibraryTab

/**
 * Tab row for switching between Library content types.
 *
 * Displays Books, Series, Authors, Narrators tabs with icons.
 * Icons fade out when the top bar collapses to save vertical space.
 * Syncs with HorizontalPager for swipe navigation.
 *
 * @param selectedTabIndex Currently selected tab index
 * @param onTabSelected Callback when a tab is selected
 * @param collapseFraction How much the top bar is collapsed (0 = expanded, 1 = collapsed)
 * @param modifier Optional modifier
 */
@Composable
fun LibraryTabRow(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    collapseFraction: Float = 0f,
    modifier: Modifier = Modifier,
) {
    // Icon fades out as top bar collapses
    val iconAlpha = 1f - collapseFraction

    SecondaryTabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        LibraryTab.entries.forEachIndexed { index, tab ->
            Tab(
                selected = selectedTabIndex == index,
                onClick = { onTabSelected(index) },
                text = { Text(tab.title) },
                icon =
                    if (iconAlpha > 0.01f) {
                        {
                            Box(modifier = Modifier.alpha(iconAlpha)) {
                                Icon(tab.icon, contentDescription = null)
                            }
                        }
                    } else {
                        null
                    },
            )
        }
    }
}
