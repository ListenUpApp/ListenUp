package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.RatingStars
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.domain.ListenerRatingLimits
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_rating_add_note
import listenup.composeapp.generated.resources.book_detail_rating_edit_note
import listenup.composeapp.generated.resources.book_detail_rating_saved_on_lift
import listenup.composeapp.generated.resources.book_detail_rating_tap_hint
import listenup.composeapp.generated.resources.book_detail_rating_yours
import listenup.composeapp.generated.resources.book_detail_readers_note
import listenup.composeapp.generated.resources.common_remove
import listenup.composeapp.generated.resources.rating_stars_unrated
import org.jetbrains.compose.resources.stringResource

/** Your stars: 36 dp glyphs that sit in 48 dp boxes once the input's own padding is counted. */
private val YourStarSize = 36.dp

/**
 * "You", the first half of the rating block: your stars as the control itself — tap or drag, saved as you
 * let go — then your note and its two actions. Stars you are dragging show here before anything saves,
 * with "Saved as you lift your finger" under them.
 *
 * @param mine Your rating, or null before you rate the book.
 * @param onSetStars Saves the stars you settled on.
 * @param onEditNote Opens the rate sheet, for the note.
 * @param onRemove Removes your rating; the block offers Undo.
 * @param isContained Whether "You" is a filled card of its own. False inside a section that is already a
 *   card (the wide layout, per A-And-Tablet), where a second card would nest one surface in another.
 */
@Composable
internal fun RatingsYouCard(
    mine: ListenerRating?,
    onSetStars: (Int) -> Unit,
    onEditNote: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    isContained: Boolean = true,
) {
    if (isContained) {
        Card(
            modifier = modifier.fillMaxWidth().testTag("yourRatingCard"),
            shape = ContentShapes.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            YourRating(
                mine = mine,
                onSetStars = onSetStars,
                onEditNote = onEditNote,
                onRemove = onRemove,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            )
        }
    } else {
        YourRating(mine = mine, onSetStars = onSetStars, onEditNote = onEditNote, onRemove = onRemove, modifier = modifier)
    }
}

@Composable
private fun YourRating(
    mine: ListenerRating?,
    onSetStars: (Int) -> Unit,
    onEditNote: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHaptics.current
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: mine?.halfStars ?: 0
    val isRated = shown >= ListenerRatingLimits.MIN_HALF_STARS

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.book_detail_rating_yours),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text =
                    if (isRated) {
                        ListenerRatingLimits.starsLabel(shown.toDouble())
                    } else {
                        stringResource(Res.string.rating_stars_unrated)
                    },
                style = if (isRated) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = if (isRated) FontWeight.Bold else FontWeight.Normal,
                color = if (isRated) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                // The stars announce the value; reading it here too would say it twice.
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
        }
        RatingStars(
            halfStars = shown,
            onHalfStarsChange = { dragging = it },
            onHalfStarsCommit = { picked ->
                dragging = null
                onSetStars(picked)
            },
            starSize = YourStarSize,
            modifier = Modifier.offset(x = (-6).dp).testTag("yourRatingStars"),
        )
        when {
            dragging != null -> {
                Hint(stringResource(Res.string.book_detail_rating_saved_on_lift))
            }

            mine != null -> {
                NoteAndActions(
                    mine = mine,
                    onEditNote = onEditNote,
                    onRemove = {
                        haptics.press()
                        onRemove()
                    },
                )
            }

            else -> {
                Hint(stringResource(Res.string.book_detail_rating_tap_hint))
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun NoteAndActions(
    mine: ListenerRating,
    onEditNote: () -> Unit,
    onRemove: () -> Unit,
) {
    mine.note?.let { note ->
        Text(
            text = stringResource(Res.string.book_detail_readers_note, note),
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = onEditNote) {
            Text(
                stringResource(
                    if (mine.note == null) Res.string.book_detail_rating_add_note else Res.string.book_detail_rating_edit_note,
                ),
            )
        }
        TextButton(onClick = onRemove) { Text(stringResource(Res.string.common_remove)) }
    }
}
