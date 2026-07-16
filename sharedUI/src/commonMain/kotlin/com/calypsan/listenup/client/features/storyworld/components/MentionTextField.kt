package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private val MIN_HEIGHT = 92.dp
private val CONTAINER_SHAPE = RoundedCornerShape(18.dp)

/**
 * The composer's free-text field: mirrors the composer ViewModel's `displayText`/`cursor`
 * (mentions already collapsed to their display name) and paints [mentionSpans] as tinted chips
 * inline via [VisualTransformation] — the displayed text and the "real" text are identical, so no
 * offset remapping is needed beyond the identity mapping.
 *
 * Holds the field value as **local** state rather than rebuilding a [TextFieldValue] from
 * [displayText]/[cursor] on every recomposition: a naive two-way binding fights the IME (marked
 * composition text, autocorrect, cursor placement all get clobbered by every VM round trip). The
 * local value is pushed up via [onChanged] on every keystroke and is only overwritten from the VM
 * state when its *text* differs from what's held locally — i.e. the VM changed the document
 * itself (mention/verb acceptance, quick-create insertion, edit-mode seeding), never merely
 * echoing back the keystroke this field just sent it.
 *
 * @param displayText The VM's current document text, mentions collapsed to their display name.
 * @param cursor The VM's caret position, in [displayText] coordinates.
 * @param mentionSpans Display-text ranges to paint as mention chips.
 * @param onChanged Called with the field's new text and caret position on every edit.
 * @param modifier Modifier for the field's container.
 * @param placeholder Hint text shown when [displayText] is empty.
 */
@Composable
fun MentionTextField(
    displayText: String,
    cursor: Int,
    mentionSpans: List<IntRange>,
    onChanged: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    var local by remember { mutableStateOf(TextFieldValue(displayText, TextRange(cursor))) }
    if (local.text != displayText) {
        local = TextFieldValue(displayText, TextRange(cursor.coerceIn(0, displayText.length)))
    }

    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val visualTransformation =
        remember(mentionSpans, primary) {
            VisualTransformation { text ->
                val spans =
                    mentionSpans.mapNotNull { range ->
                        val end = (range.last + 1).coerceIn(0, text.length)
                        val start = range.first.coerceIn(0, end)
                        if (start >= end) {
                            null
                        } else {
                            AnnotatedString.Range(
                                SpanStyle(
                                    color = primary,
                                    fontWeight = FontWeight.SemiBold,
                                    background = primary.copy(alpha = 0.12f),
                                ),
                                start,
                                end,
                            )
                        }
                    }
                TransformedText(AnnotatedString(text.text, spanStyles = spans), OffsetMapping.Identity)
            }
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MIN_HEIGHT)
                .clip(CONTAINER_SHAPE)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 15.dp, vertical = 14.dp),
    ) {
        if (displayText.isEmpty() && placeholder != null) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = local,
            onValueChange = { new ->
                local = new
                onChanged(new.text, new.selection.start)
            },
            visualTransformation = visualTransformation,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = onSurface),
            cursorBrush = SolidColor(primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
