package com.calypsan.listenup.client.features.bulkedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.bulk_edit_language
import listenup.composeapp.generated.resources.bulk_edit_multiple_values
import listenup.composeapp.generated.resources.bulk_edit_publisher
import listenup.composeapp.generated.resources.bulk_edit_year
import org.jetbrains.compose.resources.stringResource

/**
 * The bulk editor's fields.
 *
 * Every field starts **empty**, and empty means "do not touch". Where the selection already agrees
 * on a value it appears as placeholder text; where books differ the placeholder reads "Multiple
 * values". Placeholder, never value — that is what makes "the user typed something" and "this field
 * produces an instruction" the same condition, so there is no separate dirty-tracking to drift out
 * of sync with the form, and saving cannot rewrite a field nobody touched.
 *
 * Nothing is trimmed here. `actionsFor` trims when it plans, and trimming per keystroke would
 * re-render `"Tor "` as `"Tor"`, leaving no way to ever type `"Tor Books"`.
 *
 * @param state the current editing state, for its instruction values and shared-value hints.
 * @param onPublisherChange the publisher field changed.
 * @param onYearChange the year field changed; null when it is empty or not a number, which removes
 *   the instruction rather than writing an empty year.
 * @param onLanguageChange the language field changed.
 * @param modifier Modifier for the form.
 */
@Composable
fun BulkEditForm(
    state: BulkEditUiState.Editing,
    onPublisherChange: (String) -> Unit,
    onYearChange: (Int?) -> Unit,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mixed = stringResource(Res.string.bulk_edit_multiple_values)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListenUpTextField(
            value = state.publisherInput,
            onValueChange = onPublisherChange,
            label = stringResource(Res.string.bulk_edit_publisher),
            modifier = Modifier.fillMaxWidth(),
            placeholder = state.sharedPublisher ?: mixed,
        )
        ListenUpTextField(
            value = state.yearInput,
            onValueChange = { onYearChange(it.toIntOrNull()) },
            label = stringResource(Res.string.bulk_edit_year),
            modifier = Modifier.fillMaxWidth(),
            placeholder = state.sharedPublishYear?.toString() ?: mixed,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        ListenUpTextField(
            value = state.languageInput,
            onValueChange = onLanguageChange,
            label = stringResource(Res.string.bulk_edit_language),
            modifier = Modifier.fillMaxWidth(),
            placeholder = state.sharedLanguage ?: mixed,
        )
    }
}
