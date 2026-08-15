package com.calypsan.listenup.client.features.auth.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.rememberOwnedTextFieldState

/**
 * How many characters the out-of-band reset code carries. Must match the server's
 * `ResetCodeGenerator` (4 + 4, spoken as `YGFD-NBRW`) — a shorter row of cells silently
 * truncates real codes and makes completion impossible.
 */
const val CODE_LENGTH: Int = 8

/** Test tag on each rendered character cell. */
const val CODE_BOX_TAG: String = "code-box"

/** Test tag on the single real text field behind the cells. */
const val CODE_FIELD_TAG: String = "code-field"

/**
 * The reset code as eight separate cells, grouped four-and-four.
 *
 * Someone is reading this code to the requester over the phone or across a room, so the characters
 * are grouped rather than run together in one field — easier to key, and far easier to re-check
 * against what you were just told.
 *
 * There is exactly one real text field; the cells are its decoration. That keeps a single cursor,
 * one IME session and working paste, rather than six fields fighting over focus.
 *
 * Input is folded to the same alphabet the server's `normalize()` accepts — upper-cased, anything
 * outside `[0-9A-Z]` dropped — so a pasted `K4M9-TQ` and a typed `k4m9tq` both arrive as the code
 * the admin actually read out. This mirrors the server rather than adding a second rule: the
 * separator a person naturally speaks or types is the most likely mistranscription, and rejecting
 * it would fail the exact user this flow exists to rescue.
 *
 * Owns its text locally (see [rememberOwnedTextFieldState]): because the input is transformed
 * before propagating, the caller's echo never equals the raw keystroke, so a `String`-overload
 * field would churn its buffer against every stale echo and silently drop characters.
 */
@Composable
internal fun CodeBoxes(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onDone: () -> Unit = {},
) {
    val ownedText = rememberOwnedTextFieldState(value)
    BasicTextField(
        value = ownedText.fieldValue,
        onValueChange = { raw ->
            // Normalise before storing, so the local truth — and therefore the echo the caller
            // sends back — is always the code the server would read. Caret to the end of the
            // normalised text: dropped separators shift it, and the code is only ever appended to.
            val normalized = raw.text.normalizedCode()
            if (ownedText.edit(TextFieldValue(normalized, TextRange(normalized.length)))) {
                onValueChange(normalized)
            }
        },
        modifier = modifier.testTag(CODE_FIELD_TAG),
        textStyle = MaterialTheme.typography.headlineSmall,
        // The cells carry their own focus affordance; a caret floating over them would be a second,
        // conflicting one.
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Text,
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Next,
            ),
        keyboardActions = KeyboardActions(onNext = { onDone() }),
        singleLine = true,
        decorationBox = { CodeCells(ownedText.fieldValue.text, isError) },
    )
}

@Composable
private fun CodeCells(
    value: String,
    isError: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(CODE_LENGTH) { index ->
            // The gap after the fourth cell is the same break the code is spoken with.
            if (index == CODE_LENGTH / 2) {
                Box(
                    Modifier
                        .width(GROUP_DASH_WIDTH)
                        .height(GROUP_DASH_HEIGHT)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            CodeCell(value.getOrNull(index), isError)
        }
    }
}

@Composable
private fun CodeCell(
    character: Char?,
    isError: Boolean,
) {
    val container =
        if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    Box(
        modifier =
            Modifier
                .testTag(CODE_BOX_TAG)
                .size(width = CELL_WIDTH, height = CELL_HEIGHT)
                .clip(MaterialTheme.shapes.medium)
                .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = character?.toString().orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            color = ink,
            textAlign = TextAlign.Center,
        )
    }
}

/** Upper-case, `[0-9A-Z]` only, never longer than the code — the server's own normalise. */
private fun String.normalizedCode(): String = uppercase().filter { it in '0'..'9' || it in 'A'..'Z' }.take(CODE_LENGTH)

// Sized so eight cells, seven gaps and the group dash fit a compact-width phone with the
// auth screens' horizontal padding: 8×36 + 7×6 + 12 = 342dp.
private val CELL_WIDTH = 36.dp

private val CELL_HEIGHT = 52.dp

private val CELL_GAP = 6.dp

private val GROUP_DASH_WIDTH = 12.dp

private val GROUP_DASH_HEIGHT = 3.dp
