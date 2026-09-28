package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.design.theme.Spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextArea
import com.calypsan.listenup.client.design.components.RatingStars
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.domain.ListenerRatingLimits
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_rating_clear
import listenup.composeapp.generated.resources.book_detail_rating_note_counter
import listenup.composeapp.generated.resources.book_detail_rating_note_hint
import listenup.composeapp.generated.resources.book_detail_rating_save
import listenup.composeapp.generated.resources.book_detail_rating_sheet_title
import org.jetbrains.compose.resources.stringResource

/**
 * The sheet for rating a book: input stars, an optional note capped at
 * [ListenerRatingLimits.NOTE_MAX_CHARS] with a running counter, Save, and — when you have already
 * rated the book — Remove rating. It opens on your [current] rating, or on no stars; Save stays
 * disabled until at least one star is chosen.
 *
 * @param current Your existing rating, or null when you have not rated the book.
 * @param onSave Saves the chosen half stars and the (normalized) note; the sheet then closes.
 * @param onClear Removes your rating; the sheet then closes.
 * @param onDismiss Closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateBookSheet(
    current: ListenerRating?,
    onSave: (halfStars: Int, note: String?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHaptics.current
    var halfStars by rememberSaveable { mutableIntStateOf(current?.halfStars ?: 0) }
    var note by rememberSaveable { mutableStateOf(current?.note.orEmpty()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.screenMargin)
                    .padding(bottom = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.book_detail_rating_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
            )

            RatingStars(halfStars = halfStars, onHalfStarsChange = { halfStars = it })

            ListenUpTextArea(
                value = note,
                onValueChange = { note = it },
                label = stringResource(Res.string.book_detail_rating_note_hint),
                maxLength = ListenerRatingLimits.NOTE_MAX_CHARS,
                supportingText =
                    stringResource(
                        Res.string.book_detail_rating_note_counter,
                        note.length,
                        ListenerRatingLimits.NOTE_MAX_CHARS,
                    ),
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Default,
                    ),
            )

            ListenUpButton(
                text = stringResource(Res.string.book_detail_rating_save),
                enabled = halfStars >= ListenerRatingLimits.MIN_HALF_STARS,
                onClick = {
                    onSave(halfStars, ListenerRatingLimits.normalizeNote(note))
                    onDismiss()
                },
            )

            if (current != null) {
                TextButton(
                    onClick = {
                        haptics.commit()
                        onClear()
                        onDismiss()
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.book_detail_rating_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
