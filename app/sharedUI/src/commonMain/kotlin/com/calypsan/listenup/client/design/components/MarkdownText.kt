package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding

/**
 * Renders Markdown text with Material 3 theming.
 *
 * @param markdown The Markdown string to render
 * @param modifier Optional modifier
 * @param style Base text style (defaults to bodyLarge)
 * @param color Text color (defaults to onSurfaceVariant)
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Markdown(
        content = markdown,
        modifier = modifier,
        colors =
            markdownColor(
                text = color,
            ),
        typography =
            markdownTypography(
                text = style,
                paragraph = style,
            ),
        padding =
            markdownPadding(
                block = 8.dp,
            ),
    )
}
