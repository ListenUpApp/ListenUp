package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.GenreChipRow
import com.calypsan.listenup.client.design.components.MarkdownText
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.features.bookdetail.TagsSection
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_credits
import listenup.composeapp.generated.resources.book_detail_tags
import listenup.composeapp.generated.resources.common_about
import listenup.composeapp.generated.resources.common_genres
import org.jetbrains.compose.resources.stringResource

private const val DESCRIPTION_PREVIEW_MAX_HEIGHT_DP = 120
private const val DESCRIPTION_EXPAND_THRESHOLD = 200

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
 * 4. "Genres" overline + [GenreChipRow] (only when [genres] is non-empty)
 * 5. "Tags" overline + [TagsSection] with header suppressed (only when [tags] is non-empty or loading)
 *
 * @param description   Markdown-formatted book description.
 * @param genres        Genre names to display as outlined chips.
 * @param tags          Tags to display as filled chips.
 * @param isLoadingTags True while tags are being fetched; [TagsSection] shows a loading state.
 * @param isCard        When true, wraps content in a [surfaceContainerLow] card; otherwise
 *                      renders frameless.
 * @param isDescriptionExpanded   Whether the description is currently fully expanded.
 * @param onToggleDescriptionExpanded   Called when the user taps "Read more" / "Read less".
 * @param onGenreClick  Optional callback when a genre chip is tapped.
 * @param onTagClick    Called when a tag chip is tapped.
 * @param modifier      Modifier for the outermost container.
 * @param creditsSlot   Optional composable rendered between the description and the Genres row.
 *                      A "Credits" overline is shown automatically when this slot is non-null.
 */
@Composable
fun AboutSection(
    description: String,
    genres: List<String>,
    tags: List<Tag>,
    isLoadingTags: Boolean,
    isCard: Boolean,
    isDescriptionExpanded: Boolean,
    onToggleDescriptionExpanded: () -> Unit,
    onGenreClick: ((String) -> Unit)?,
    onTagClick: (Tag) -> Unit,
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
            Text(
                text = stringResource(Res.string.common_about),
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            AboutDescriptionBlock(
                description = description,
                isExpanded = isDescriptionExpanded,
                onToggleExpanded = onToggleDescriptionExpanded,
            )

            AboutClassificationBlocks(
                creditsSlot = creditsSlot,
                genres = genres,
                onGenreClick = onGenreClick,
                tags = tags,
                isLoadingTags = isLoadingTags,
                onTagClick = onTagClick,
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

/** Expandable description body with the inline Read-more / Read-less toggle. */
@Composable
private fun AboutDescriptionBlock(
    description: String,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Box(
        modifier =
            if (isExpanded) {
                Modifier
            } else {
                Modifier
                    .heightIn(max = DESCRIPTION_PREVIEW_MAX_HEIGHT_DP.dp)
                    .clip(RoundedCornerShape(0.dp))
            },
    ) {
        MarkdownText(
            markdown = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (description.length > DESCRIPTION_EXPAND_THRESHOLD) {
        TextButton(
            onClick = onToggleExpanded,
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(if (isExpanded) "Read less" else "Read more")
        }
    }
}

/**
 * Credits slot (optional), Genres row, and Tags section — the lower classification blocks.
 *
 * Each block is preceded by a [Spacing.sectionGap] spacer. A matching [SectionOverline] is shown
 * for each block; [TagsSection] is rendered with [showHeader] = false to avoid a double label.
 */
@Composable
private fun AboutClassificationBlocks(
    creditsSlot: (@Composable () -> Unit)?,
    genres: List<String>,
    onGenreClick: ((String) -> Unit)?,
    tags: List<Tag>,
    isLoadingTags: Boolean,
    onTagClick: (Tag) -> Unit,
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
        GenreChipRow(
            genres = genres,
            onGenreClick = onGenreClick,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
        )
    }

    if (tags.isNotEmpty() || isLoadingTags) {
        Spacer(modifier = Modifier.height(Spacing.sectionGap))
        SectionOverline(text = stringResource(Res.string.book_detail_tags))
        Spacer(modifier = Modifier.height(Spacing.titleGap))
        TagsSection(
            tags = tags,
            isLoading = isLoadingTags,
            onTagClick = onTagClick,
            showHeader = false,
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
