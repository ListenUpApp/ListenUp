package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.text.forProse
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding

/**
 * Renders Markdown text with Material 3 theming, as prose: each paragraph aligned to its own start ([forProse]).
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
    // Prose: each paragraph aligns to its own start, so an Arabic description in an English layout runs flush right.
    val prose = style.forProse()
    val type = MaterialTheme.typography
    Markdown(
        content = markdown,
        modifier = modifier,
        colors =
            markdownColor(
                text = color,
            ),
        typography =
            markdownTypography(
                h1 = type.displayLarge.forProse(),
                h2 = type.displayMedium.forProse(),
                h3 = type.displaySmall.forProse(),
                h4 = type.headlineMedium.forProse(),
                h5 = type.headlineSmall.forProse(),
                h6 = type.titleLarge.forProse(),
                text = prose,
                quote = type.bodyMedium.forProse(),
                paragraph = prose,
                ordered = prose,
                bullet = prose,
                list = prose,
            ),
        padding =
            markdownPadding(
                block = 8.dp,
            ),
    )
}
