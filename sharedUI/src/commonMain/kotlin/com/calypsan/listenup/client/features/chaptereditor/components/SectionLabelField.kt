package com.calypsan.listenup.client.features.chaptereditor.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.calypsan.listenup.client.design.components.ListenUpTextField
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_section_label_hint

/**
 * Free-form label field for a Structure-lens header row (a Book or Part group's own title, e.g.
 * "Part One" or "Volume II"). Deliberately never pre-filled — the placeholder hint only explains
 * what belongs here ("whatever the book calls it"); it never guesses or defaults a value, since a
 * synthesized label would misrepresent the book's own structure.
 *
 * @param value The group's current label, or empty when unset.
 * @param onValueChange Fired on every keystroke; the caller normalizes blank to `null` before
 *   calling into the ViewModel (mirrors `ChapterEditorViewModel.setSectionLabel`).
 */
@Composable
fun SectionLabelField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListenUpTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = stringResource(Res.string.chapter_editor_section_label_hint),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions.Default,
        modifier = modifier,
    )
}
