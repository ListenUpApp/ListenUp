package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Emphasise the first case-insensitive occurrence of [query] inside [text] in the accent colour —
 * the canonical client-side search-match highlight. Returns the plain text unchanged when [query]
 * is blank or absent, so callers can pass it straight into a `Text`.
 *
 * @param text Full label to render.
 * @param query Substring to emphasise (case-insensitive, first match only).
 */
@Composable
fun highlightMatch(
    text: String,
    query: String,
): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    val index = text.indexOf(query, ignoreCase = true)
    if (query.isBlank() || index < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, index))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.ExtraBold)) {
            append(text.substring(index, index + query.length))
        }
        append(text.substring(index + query.length))
    }
}
