package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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

/**
 * A single genre chip using Material theme colors.
 *
 * @param genre The genre name to display
 * @param onClick Optional click handler for browsing by genre
 * @param modifier Modifier for the chip
 */
@Composable
fun GenreChip(
    genre: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Text(
        text = genre,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                ).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * A row of genre chips that wraps to multiple lines.
 *
 * @param genres List of genre names
 * @param onGenreClick Optional callback when a genre is clicked
 * @param modifier Modifier for the row
 * @param horizontalArrangement Horizontal arrangement for the chips (defaults to center)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenreChipRow(
    genres: List<String>,
    onGenreClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
) {
    if (genres.isEmpty()) return

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        genres.forEach { genre ->
            GenreChip(
                genre = genre,
                onClick = onGenreClick?.let { { it(genre) } },
            )
        }
    }
}
