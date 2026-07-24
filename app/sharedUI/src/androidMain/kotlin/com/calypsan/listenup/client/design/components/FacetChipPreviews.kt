package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpTheme

@Preview(name = "Facet chips — outlined / secondary / tertiary")
@Composable
private fun PreviewFacetChips() {
    ListenUpTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Genre — outlined, no icon, larger label.
            FacetChipRow(
                labels = listOf("Fantasy", "Epic Fantasy", "High Fantasy"),
                facet = BookFacet.Genre,
            )
            // Tag — filled secondaryContainer with a leading tag glyph.
            FacetChipRow(
                labels = listOf("Award Winner", "Staff Pick", "Owned"),
                facet = BookFacet.Tag,
            )
            // Mood — filled tertiaryContainer with a leading mood glyph.
            FacetChipRow(
                labels = listOf("Dark", "Epic", "Tense"),
                facet = BookFacet.Mood,
            )
        }
    }
}
