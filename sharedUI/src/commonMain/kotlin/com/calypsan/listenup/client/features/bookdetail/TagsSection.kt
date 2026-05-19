package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.domain.model.Tag
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_tags

/**
 * Section displaying tags for a book.
 *
 * Tags are global community descriptors displayed as clickable chips.
 * Clicking a tag navigates to the TagDetailScreen.
 * Editing tags is available on the Edit Book page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsSection(
    tags: List<Tag>,
    isLoading: Boolean,
    onTagClick: (Tag) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty() && !isLoading) return

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        // Header - matches "About" and "Chapters" heading style
        Text(
            text = stringResource(Res.string.book_detail_tags),
            style =
                MaterialTheme.typography.titleMedium.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
            modifier = Modifier.padding(bottom = 12.dp),
        )

        // Tags (left-aligned via Arrangement.Start)
        if (tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.forEach { tag ->
                    TagChip(
                        tag = tag,
                        onClick = { onTagClick(tag) },
                    )
                }
            }
        }
    }
}

/**
 * A clickable tag chip styled consistently with GenreChip.
 *
 * Uses secondaryContainer background to match genre pills.
 */
@Composable
private fun TagChip(
    tag: Tag,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = tag.displayName(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
