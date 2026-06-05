package com.calypsan.listenup.client.features.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.domain.model.Shelf
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_my_shelves

/**
 * "My shelves" section: a [SectionTitle] with a "See all" action over the shelves, color-blocked.
 *
 * Adaptive: a full-width vertical stack on wide windows; a horizontal carousel of fixed-width cards
 * on compact. Container colors cycle through the primary/tertiary/secondary container roles.
 *
 * @param shelves List of shelves owned by the user.
 * @param isWide Whether the window is medium+ (stack vs carousel).
 * @param onShelfClick Callback when a shelf is clicked.
 * @param onSeeAllClick Callback when "See all" is clicked.
 * @param modifier Optional modifier.
 */
@Composable
fun MyShelvesRow(
    shelves: List<Shelf>,
    isWide: Boolean,
    onShelfClick: (String) -> Unit,
    onSeeAllClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionTitle(
            title = stringResource(Res.string.home_my_shelves),
            onSeeAll = onSeeAllClick,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isWide) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                shelves.forEachIndexed { index, shelf ->
                    val (container, content) = shelfColors(index)
                    ShelfCard(
                        shelf = shelf,
                        containerColor = container,
                        contentColor = content,
                        onClick = { onShelfClick(shelf.id) },
                        fillWidth = true,
                    )
                }
            }
        } else {
            BrowseCarousel(items = shelves) { shelf ->
                val (container, content) = shelfColors(shelves.indexOf(shelf))
                ShelfCard(
                    shelf = shelf,
                    containerColor = container,
                    contentColor = content,
                    onClick = { onShelfClick(shelf.id) },
                )
            }
        }
    }
}

/** Cycles the container/on-container color pair so adjacent shelves read distinctly. */
@Composable
private fun shelfColors(index: Int): Pair<Color, Color> =
    when (index % 3) {
        0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        1 -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
