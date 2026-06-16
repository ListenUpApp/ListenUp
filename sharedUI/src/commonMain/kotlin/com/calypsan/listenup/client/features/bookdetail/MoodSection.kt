package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.domain.model.Mood
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_mood
import org.jetbrains.compose.resources.stringResource

/**
 * Section displaying moods for a book — the affective axis ("how it feels").
 *
 * Moods are global, curator-applied descriptors (e.g. "Feel-Good", "Tense", "Witty")
 * rendered as filled `tertiaryContainer` pills with a leading mood glyph. The tertiary
 * accent sets moods apart from the outlined genre chips ("where it lives") and the
 * `secondaryContainer` tag chips ("tropes") — three visually distinct classification axes.
 *
 * Moods are display-only; there is no per-mood detail destination yet, so the chips
 * carry no click handler.
 *
 * Renders nothing when [moods] is empty.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoodSection(
    moods: List<Mood>,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    if (moods.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        if (showHeader) {
            // Header - matches "About", "Chapters", and "Tags" heading style
            Text(
                text = stringResource(Res.string.book_detail_mood),
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        // Moods (left-aligned via Arrangement.Start)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            moods.forEach { mood ->
                MoodChip(mood = mood)
            }
        }
    }
}

/**
 * A mood chip — filled `tertiaryContainer` pill with a leading mood glyph.
 *
 * The tertiary accent is the third classification colour on Book Detail: genres are
 * outlined, tags are `secondaryContainer`, moods are `tertiaryContainer` — so the
 * three axes read as distinct at a glance.
 */
@Composable
private fun MoodChip(
    mood: Mood,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Mood,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            text = mood.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}
