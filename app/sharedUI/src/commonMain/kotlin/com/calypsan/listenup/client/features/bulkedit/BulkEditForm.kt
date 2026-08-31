package com.calypsan.listenup.client.features.bulkedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.bulk_edit_clear_field
import listenup.composeapp.generated.resources.bulk_edit_consequence_agreed_one
import listenup.composeapp.generated.resources.bulk_edit_consequence_agreed_plural
import listenup.composeapp.generated.resources.bulk_edit_consequence_differs_one
import listenup.composeapp.generated.resources.bulk_edit_consequence_differs_plural
import listenup.composeapp.generated.resources.bulk_edit_consequence_written_none
import listenup.composeapp.generated.resources.bulk_edit_consequence_written_one
import listenup.composeapp.generated.resources.bulk_edit_consequence_written_plural
import listenup.composeapp.generated.resources.bulk_edit_consequence_written_single_book
import listenup.composeapp.generated.resources.bulk_edit_language
import listenup.composeapp.generated.resources.bulk_edit_multiple_values
import listenup.composeapp.generated.resources.bulk_edit_publisher
import listenup.composeapp.generated.resources.bulk_edit_year
import org.jetbrains.compose.resources.stringResource

/** Gap between the three field blocks; each block is a field plus its consequence line. */
private val FieldGap = 20.dp

/** The consequence line's leading glyph is a marker, not an illustration — keep it small. */
private val ConsequenceIconSize = 14.dp

/**
 * The bulk editor's fields.
 *
 * Every field starts **empty**, and empty means "do not touch". Where the selection already agrees
 * on a value it appears as placeholder text; where books differ the placeholder reads "Multiple
 * values". Placeholder, never value — that is what makes "the user typed something" and "this field
 * produces an instruction" the same condition, so there is no separate dirty-tracking to drift out
 * of sync with the form, and saving cannot rewrite a field nobody touched.
 *
 * A placeholder is only safe to read if the reader knows it will not be written, so every field
 * carries a consequence line saying so in words. That sentence is the feature: the outline colour
 * tells you the field changed, the sentence tells you what changing it *does* — to how many books,
 * and how many it leaves alone.
 *
 * Nothing is trimmed here. `actionsFor` trims when it plans, and trimming per keystroke would
 * re-render `"Tor "` as `"Tor"`, leaving no way to ever type `"Tor Books"`.
 *
 * @param state the current editing state, for its instruction values, shared-value hints and the
 *   per-field counts the consequence lines are built from.
 * @param onPublisherChange the publisher field changed.
 * @param onYearChange the year field changed; null when it is empty or not a number, which removes
 *   the instruction rather than writing an empty year.
 * @param onLanguageChange the language field changed.
 * @param modifier Modifier for the form.
 * @param stacked true for one field per row (the phone), false to pair the year and the language
 *   beneath a full-width publisher — the shape Book Edit's Publishing card takes on a wide window.
 */
@Composable
fun BulkEditForm(
    state: BulkEditUiState.Editing,
    onPublisherChange: (String) -> Unit,
    onYearChange: (Int?) -> Unit,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    stacked: Boolean = true,
) {
    val mixed = stringResource(Res.string.bulk_edit_multiple_values)
    val enabled = !state.isApplying

    val publisher: @Composable (Modifier) -> Unit = { fieldModifier ->
        PublishingField(
            value = state.publisherInput,
            onValueChange = onPublisherChange,
            onClear = { onPublisherChange("") },
            label = stringResource(Res.string.bulk_edit_publisher),
            placeholder = state.sharedPublisher ?: mixed,
            leadingIcon = Icons.Outlined.Business,
            consequence = state.consequenceOf<BulkEdit.SetPublisher>(state.sharedPublisher),
            enabled = enabled,
            modifier = fieldModifier,
        )
    }
    val year: @Composable (Modifier) -> Unit = { fieldModifier ->
        PublishingField(
            value = state.yearInput,
            onValueChange = { onYearChange(it.toIntOrNull()) },
            onClear = { onYearChange(null) },
            label = stringResource(Res.string.bulk_edit_year),
            placeholder = state.sharedPublishYear?.toString() ?: mixed,
            leadingIcon = Icons.Outlined.CalendarMonth,
            consequence = state.consequenceOf<BulkEdit.SetPublishYear>(state.sharedPublishYear?.toString()),
            enabled = enabled,
            modifier = fieldModifier,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    val language: @Composable (Modifier) -> Unit = { fieldModifier ->
        PublishingField(
            value = state.languageInput,
            onValueChange = onLanguageChange,
            onClear = { onLanguageChange("") },
            label = stringResource(Res.string.bulk_edit_language),
            placeholder = state.sharedLanguage ?: mixed,
            leadingIcon = Icons.Outlined.Language,
            consequence = state.consequenceOf<BulkEdit.SetLanguage>(state.sharedLanguage),
            enabled = enabled,
            modifier = fieldModifier,
        )
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FieldGap)) {
        publisher(Modifier.fillMaxWidth())
        if (stacked) {
            year(Modifier.fillMaxWidth())
            language(Modifier.fillMaxWidth())
        } else {
            // The publisher keeps the full width even here: a leading icon plus a real publisher
            // name at half width ellipses, and a value that ellipses is exactly the small lie this
            // screen cannot afford. A year and a language both fit.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FieldGap)) {
                year(Modifier.weight(1f))
                language(Modifier.weight(1f))
            }
        }
    }
}

/**
 * One field, the sentence that says what leaving it — or not — will do, and the way back.
 *
 * An untouched field and a touched one have to be unmistakable, because the whole safety of the
 * screen rests on reading them apart: an untouched one shows what the books already say and writes
 * nothing, a touched one is an instruction that will be written to as many books as the sentence
 * beneath it names. So they differ on four signals at once — outline colour, label colour, leading
 * icon tint, and the presence of a clear button — not one. One signal can be missed.
 *
 * The clear button is the way back, and it is not a nicety: on this screen a stray keystroke arms a
 * forty-book write with no undo, and clearing the field is what disarms it. It reports the empty
 * value through the same handler as typing, so removing an instruction and never making one stay
 * the same code path.
 */
@Composable
private fun PublishingField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    label: String,
    placeholder: String,
    leadingIcon: ImageVector,
    consequence: FieldConsequence,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val armed = value.isNotEmpty()
    Column(modifier) {
        ListenUpTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            enabled = enabled,
            keyboardOptions = keyboardOptions,
            leadingIcon = leadingIcon,
            colors = if (armed) armedFieldColors() else null,
            trailingContent =
                if (!armed) {
                    null
                } else {
                    {
                        IconButton(onClick = onClear, enabled = enabled) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(Res.string.bulk_edit_clear_field, label),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
        )
        ConsequenceLine(consequence)
    }
}

/**
 * The accent an armed field wears while it is not focused.
 *
 * Material already shouts about the *focused* field; this screen has to shout about the field that
 * holds an instruction, which is a different and longer-lived fact — you type a publisher, move on,
 * and only look back at the form once, right before committing. Only the unfocused roles are
 * overridden, so focus still reads as focus on top of it.
 */
@Composable
private fun armedFieldColors(): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLeadingIconColor = MaterialTheme.colorScheme.primary,
    )

/**
 * What this field will do to the selection, in a whole sentence.
 *
 * Tinted and arrowed only when it promises movement. A typed value the whole selection already
 * holds gets the quiet treatment — an accent there would promise a change the preview underneath is
 * about to deny, and two parts of one screen disagreeing is worse than either being silent.
 */
@Composable
private fun ConsequenceLine(consequence: FieldConsequence) {
    val colors = MaterialTheme.colorScheme
    val loud = consequence.writes
    val tint = if (loud) colors.primary else colors.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = consequence.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(ConsequenceIconSize).padding(top = 2.dp),
        )
        Text(
            text = consequence.text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (loud) FontWeight.Bold else FontWeight.Medium,
            color = tint,
        )
    }
}

/**
 * One field's consequence line, resolved.
 *
 * @property text the whole sentence, already localized and counted.
 * @property icon the marker: an arrow when something will be written, a dash when a typed value
 *   turns out to change nothing, an open padlock when the field is untouched and safe.
 * @property writes true only when at least one book would actually be written to — the single
 *   condition that earns the accent colour.
 */
private data class FieldConsequence(
    val text: String,
    val icon: ImageVector,
    val writes: Boolean,
)

/**
 * The consequence of the instruction of type [T], given the value the selection agrees on.
 *
 * Three states, and they are the whole point of the screen:
 *  - **typed** — the field has produced an instruction, so it counts the books that would actually
 *    change. The count is this field's own preview row, never the screen's total: a field whose
 *    value 28 books already hold says twelve, not forty.
 *  - **agreed** — untouched, and every loaded book already says the same thing. The value is named
 *    so the reader can see what they would be replacing without risking it.
 *  - **differs** — untouched, and there is no single value to name. With one book that means the
 *    field is simply empty; with more, that the books disagree. Either way nothing is written.
 */
@Composable
private inline fun <reified T : BulkEdit> BulkEditUiState.Editing.consequenceOf(
    sharedValue: String?,
): FieldConsequence {
    val typed = edits.filterIsInstance<T>().lastOrNull() != null
    if (typed) {
        val affected = preview.firstOrNull { it.edit is T }?.affectedCount ?: 0
        val arrow = Icons.AutoMirrored.Outlined.ArrowForward
        return when {
            affected == 0 -> {
                FieldConsequence(
                    text = stringResource(Res.string.bulk_edit_consequence_written_none),
                    icon = Icons.Outlined.Remove,
                    writes = false,
                )
            }

            bookCount == 1 -> {
                FieldConsequence(
                    text = stringResource(Res.string.bulk_edit_consequence_written_single_book),
                    icon = arrow,
                    writes = true,
                )
            }

            affected == 1 -> {
                FieldConsequence(
                    text = stringResource(Res.string.bulk_edit_consequence_written_one, bookCount),
                    icon = arrow,
                    writes = true,
                )
            }

            else -> {
                FieldConsequence(
                    text = stringResource(Res.string.bulk_edit_consequence_written_plural, affected, bookCount),
                    icon = arrow,
                    writes = true,
                )
            }
        }
    }
    val text =
        when {
            sharedValue != null && bookCount == 1 -> {
                stringResource(Res.string.bulk_edit_consequence_agreed_one, sharedValue)
            }

            sharedValue != null -> {
                stringResource(Res.string.bulk_edit_consequence_agreed_plural, bookCount, sharedValue)
            }

            bookCount == 1 -> {
                stringResource(Res.string.bulk_edit_consequence_differs_one)
            }

            else -> {
                stringResource(Res.string.bulk_edit_consequence_differs_plural, bookCount)
            }
        }
    return FieldConsequence(text = text, icon = Icons.Outlined.LockOpen, writes = false)
}
