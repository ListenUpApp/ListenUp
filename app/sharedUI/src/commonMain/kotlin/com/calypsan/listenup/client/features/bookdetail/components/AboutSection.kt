package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.BookFacet
import com.calypsan.listenup.client.design.components.FacetChipRow
import com.calypsan.listenup.client.design.components.MarkdownText
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.Tag
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_about_this_book
import listenup.composeapp.generated.resources.book_detail_credits
import listenup.composeapp.generated.resources.book_detail_mood
import listenup.composeapp.generated.resources.book_detail_tags
import listenup.composeapp.generated.resources.common_genres
import listenup.composeapp.generated.resources.common_read_less
import listenup.composeapp.generated.resources.common_read_more
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * Grouped "About" section combining description, optional credits, genres, and tags.
 *
 * On wide layouts ([isCard] = true) the content is wrapped in a `surfaceContainerLow` card with
 * rounded corners and inner padding — visually lifting it off the surface as a coherent unit.
 * On compact layouts ([isCard] = false) the content renders frameless in the scroll flow.
 *
 * Content order:
 * 1. "About" heading
 * 2. Expandable [description] via [MarkdownText] with inline Read-more toggle
 * 3. [creditsSlot] (if provided), prefixed with a "Credits" overline
 * 4. "Genres" overline + outlined [FacetChipRow] (only when [genres] is non-empty)
 * 5. "Tags" overline + filled-secondary [FacetChipRow] (only when [tags] is non-empty or loading)
 * 6. "Moods" overline + filled-tertiary [FacetChipRow] (only when [moods] is non-empty)
 *
 * @param description   Markdown-formatted book description.
 * @param genres        Genres to display as outlined chips; a tap browses that genre's books.
 * @param tags          Tags to display as filled `secondaryContainer` chips.
 * @param moods         Moods to display as filled `tertiaryContainer` chips (the affective axis).
 * @param isLoadingTags True while tags are being fetched; the Tags block stays visible meanwhile.
 * @param isCard        When true, wraps content in a [surfaceContainerLow] card; otherwise
 *                      renders frameless.
 * @param collapsedLines How many lines the description shows before "Read more" (spec §3.2.1 D29: four while
 *                      deciding, two otherwise, on phones); null shows the whole text with no toggle (wide layouts).
 * @param isDescriptionExpanded   Whether the description is currently fully expanded.
 * @param onToggleDescriptionExpanded   Called when the user taps "Read more" / "Read less".
 * @param onGenreClick  Optional callback when a genre chip is tapped.
 * @param onTagClick    Called when a tag chip is tapped.
 * @param onMoodClick   Called when a mood chip is tapped.
 * @param modifier      Modifier for the outermost container.
 * @param creditsSlot   Optional composable rendered between the description and the Genres row.
 *                      A "Credits" overline is shown automatically when this slot is non-null.
 */
@Composable
fun AboutSection(
    description: String,
    genres: List<Genre>,
    tags: List<Tag>,
    moods: List<Mood>,
    isLoadingTags: Boolean,
    isCard: Boolean,
    collapsedLines: Int?,
    isDescriptionExpanded: Boolean,
    onToggleDescriptionExpanded: () -> Unit,
    onGenreClick: ((Genre) -> Unit)?,
    onTagClick: (Tag) -> Unit,
    onMoodClick: (Mood) -> Unit,
    modifier: Modifier = Modifier,
    creditsSlot: (@Composable () -> Unit)? = null,
) {
    val innerPadding = if (isCard) Spacing.screenMargin else 0.dp

    val content: @Composable () -> Unit = {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(innerPadding),
        ) {
            // Medium: the hero title is the screen's one display moment (rev 7 type rule).
            Text(
                text = stringResource(Res.string.book_detail_about_this_book),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp),
            )

            AboutDescriptionBlock(
                description = description,
                collapsedLines = collapsedLines,
                isExpanded = isDescriptionExpanded,
                onToggleExpanded = onToggleDescriptionExpanded,
            )

            AboutClassificationBlocks(
                creditsSlot = creditsSlot,
                genres = genres,
                onGenreClick = onGenreClick,
                tags = tags,
                moods = moods,
                isLoadingTags = isLoadingTags,
                onTagClick = onTagClick,
                onMoodClick = onMoodClick,
            )
        }
    }

    if (isCard) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = ContentShapes.card,
            modifier = modifier,
            content = content,
        )
    } else {
        Box(modifier = modifier) {
            content()
        }
    }
}

/**
 * The description, clamped to [collapsedLines] until expanded. Whether it overflows is measured, not guessed from its
 * length: "Read more" appears only when the clamp actually hides something, and it is a real button.
 */
@Composable
private fun AboutDescriptionBlock(
    description: String,
    collapsedLines: Int?,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val haptics = LocalHaptics.current
    // Remembers the last measurement, so "Read less" stays offered once the clamp is off.
    var overflows by remember(description, collapsedLines) { mutableStateOf(false) }
    val style = MaterialTheme.typography.bodyMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant

    if (collapsedLines == null || isExpanded) {
        MarkdownText(markdown = description, style = style, color = color)
    } else {
        ClampedMarkdownPreview(
            markdown = description,
            maxLines = collapsedLines,
            style = style,
            color = color,
            onOverflowChange = { overflows = it },
        )
    }

    if (collapsedLines != null && overflows) {
        TextButton(
            onClick = {
                haptics.press()
                onToggleExpanded()
            },
            contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
        ) {
            Text(
                text = stringResource(if (isExpanded) Res.string.common_read_less else Res.string.common_read_more),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * Renders [markdown] clamped to [maxLines] of [style] height, the bottom edge alpha-fading to transparent when it is
 * cut — the styled-markdown analogue of a `maxLines` text clamp. The content is measured at its full height and
 * [onOverflowChange] says whether the clamp hid any of it. The fade is alpha-based (DstIn), so it is independent of
 * whatever background the section sits on.
 */
@Composable
private fun ClampedMarkdownPreview(
    markdown: String,
    maxLines: Int,
    style: TextStyle,
    color: Color,
    onOverflowChange: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val maxHeight = with(density) { (style.lineHeight * maxLines.toFloat()).toDp() }
    val lineHeightPx = with(density) { style.lineHeight.roundToPx() }
    var isCut by remember(markdown, maxLines) { mutableStateOf(false) }
    val currentOnOverflowChange by rememberUpdatedState(onOverflowChange)
    LaunchedEffect(isCut) { currentOnOverflowChange(isCut) }
    Layout(
        content = { MarkdownText(markdown = markdown, style = style, color = color) },
        modifier =
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (isCut) {
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    0f to Color.Black,
                                    0.8f to Color.Black,
                                    1f to Color.Transparent,
                                ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
    ) { measurables, constraints ->
        // Measured unbounded, so the full height is known; laid out no taller than the clamp when it cuts. A hidden
        // line costs a whole line height, so half a line of slack keeps the renderer's paragraph padding from
        // reading as overflow.
        val placeable = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val limit = maxHeight.roundToPx()
        val cut = placeable.height > limit + lineHeightPx / 2
        isCut = cut
        layout(placeable.width, if (cut) limit else placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * Credits slot (optional), then the three classification facet rows — Genres, Tags, Moods.
 *
 * Each block is preceded by a [Spacing.sectionGap] spacer and a matching [SectionOverline].
 * All three chip rows render through the single [FacetChipRow] component, switched by
 * [BookFacet]; the tag row uses the generic overload so each chip closes its click over its own
 * [Tag] — robust to display-name collisions. The [isLoadingTags] guard keeps the Tags block
 * visible while tags are fetched.
 */
@Composable
private fun AboutClassificationBlocks(
    creditsSlot: (@Composable () -> Unit)?,
    genres: List<Genre>,
    onGenreClick: ((Genre) -> Unit)?,
    tags: List<Tag>,
    moods: List<Mood>,
    isLoadingTags: Boolean,
    onTagClick: (Tag) -> Unit,
    onMoodClick: (Mood) -> Unit,
) {
    if (creditsSlot != null) {
        Spacer(modifier = Modifier.height(Spacing.sectionGap))
        SectionOverline(text = stringResource(Res.string.book_detail_credits))
        Spacer(modifier = Modifier.height(Spacing.titleGap))
        creditsSlot()
    }

    if (genres.isNotEmpty()) {
        Spacer(modifier = Modifier.height(Spacing.sectionGap))
        SectionOverline(text = stringResource(Res.string.common_genres))
        Spacer(modifier = Modifier.height(Spacing.titleGap))
        FacetChipRow(
            items = genres,
            facet = BookFacet.Genre,
            label = { it.name },
            onClick = onGenreClick,
        )
    }

    if (tags.isNotEmpty() || isLoadingTags) {
        Spacer(modifier = Modifier.height(Spacing.sectionGap))
        SectionOverline(text = stringResource(Res.string.book_detail_tags))
        Spacer(modifier = Modifier.height(Spacing.titleGap))
        FacetChipRow(
            items = tags,
            facet = BookFacet.Tag,
            label = { it.displayName() },
            onClick = onTagClick,
        )
    }

    if (moods.isNotEmpty()) {
        Spacer(modifier = Modifier.height(Spacing.sectionGap))
        SectionOverline(text = stringResource(Res.string.book_detail_mood))
        Spacer(modifier = Modifier.height(Spacing.titleGap))
        FacetChipRow(
            items = moods,
            facet = BookFacet.Mood,
            label = { it.displayName() },
            onClick = onMoodClick,
        )
    }
}

/**
 * Small uppercase overline label used to introduce a sub-section within [AboutSection].
 *
 * Renders [text] in `labelMedium` with tight letter-spacing and `onSurfaceVariant` colour —
 * matching the design's "Credits" and "Genres" overline treatment.
 */
@Composable
private fun SectionOverline(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style =
            MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 1.sp,
            ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
