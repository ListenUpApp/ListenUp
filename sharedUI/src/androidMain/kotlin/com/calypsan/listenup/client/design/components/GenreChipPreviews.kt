package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpTheme

@OptIn(ExperimentalLayoutApi::class)
@Preview(name = "Genre chips — outlined")
@Composable
private fun PreviewGenreChips() {
    ListenUpTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GenreChipRow(
                genres = listOf("Fantasy", "Epic Fantasy", "High Fantasy"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Preview(name = "Genre outlined vs Tag filled — contrast")
@Composable
private fun PreviewGenreVsTagContrast() {
    ListenUpTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Outlined genre chips
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Fantasy", "Adventure").forEach { genre ->
                    GenreChip(genre = genre)
                }
            }
            // Filled tag chips (secondaryContainer) — visually distinct family
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("magic-system", "chosen-one").forEach { tag ->
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
