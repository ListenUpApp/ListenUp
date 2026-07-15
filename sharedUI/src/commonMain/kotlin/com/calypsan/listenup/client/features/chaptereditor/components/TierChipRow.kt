package com.calypsan.listenup.client.features.chaptereditor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Header row for a Structure-lens Book/Part group: a renamable tier-vocabulary [AssistChip] (the
 * *type* word, e.g. "Part" — shared by every header of this tier, so editing it here renames the
 * book's whole vocabulary via [onTierWordCommit]) beside a [SectionLabelField] for this specific
 * group's own free-form title (e.g. "Part One", via [onSectionLabelCommit]).
 *
 * The two are independent concepts: renaming the tier word never touches any group's own label,
 * and vice versa — matching `ChapterEditorViewModel.setTierLabel` / `ChapterEditorViewModel.setSectionLabel`
 * staying separate operations.
 *
 * @param tierWord The tier's current vocabulary word (already defaulted by the caller, e.g. to
 *   "Part", when the book hasn't named it).
 * @param onTierWordCommit Fired once, when the inline chip editor commits (Done/blur).
 * @param sectionLabel This group's own label, or `null` when unset.
 * @param onSectionLabelCommit Fired on every keystroke of the section-label field.
 */
@Composable
fun TierChipRow(
    tierWord: String,
    onTierWordCommit: (String) -> Unit,
    sectionLabel: String?,
    onSectionLabelCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingTierWord by remember { mutableStateOf(false) }
    var tierWordDraft by remember(tierWord) { mutableStateOf(tierWord) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editingTierWord) {
            // A bare OutlinedTextField (not the design-system ListenUpTextField, which always
            // fillMaxWidth()s) — this needs to stay chip-sized so it doesn't crowd out the
            // SectionLabelField sharing this row.
            OutlinedTextField(
                value = tierWordDraft,
                onValueChange = { tierWordDraft = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            editingTierWord = false
                            onTierWordCommit(tierWordDraft)
                        },
                    ),
                modifier = Modifier.width(120.dp),
            )
        } else {
            AssistChip(
                onClick = { editingTierWord = true },
                label = {
                    Text(
                        text = tierWord,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }

        SectionLabelField(
            value = sectionLabel.orEmpty(),
            onValueChange = onSectionLabelCommit,
            modifier = Modifier.weight(1f),
        )
    }
}
