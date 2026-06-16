package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.BookFacet
import com.calypsan.listenup.client.design.components.FacetChipRow
import com.calypsan.listenup.client.design.components.MarkdownText
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.util.toPlainTextPreview
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_about_this_book
import listenup.composeapp.generated.resources.book_detail_credits
import listenup.composeapp.generated.resources.book_detail_mood
import listenup.composeapp.generated.resources.book_detail_read_less
import listenup.composeapp.generated.resources.book_detail_read_more
import listenup.composeapp.generated.resources.book_detail_tags
import listenup.composeapp.generated.resources.common_genres
import org.jetbrains.compose.resources.stringResource

private const val DESCRIPTION_PREVIEW_MAX_LINES = 4
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
 * 4. "Genres" overline + outlined [FacetChipRow] (only when [genres] is non-empty)
 * 5. "Tags" overline + filled-secondary [FacetChipRow] (only when [tags] is non-empty or loading)
 * 6. "Moods" overline + filled-tertiary [FacetChipRow] (only when [moods] is non-empty)
 *
 * @param description   Markdown-formatted book description.
 * @param genres        Genre names to display as outlined chips.
 * @param tags          Tags to display as filled `secondaryContainer` chips.
 * @param moods         Moods to display as filled `tertiaryContainer` chips (the affective axis).
 * @param isLoadingTags True while tags are being fetched; the Tags block stays visible meanwhile.
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
    moods: List<Mood>,
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
                text = stringResource(Res.string.book_detail_about_this_book),
                style =
                    MaterialTheme.typography.titleLarge.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
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
                moods = moods,
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
    if (isExpanded) {
        MarkdownText(
            markdown = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        // Collapsed preview: a clean maxLines clamp with ellipsis (no mid-line height crop) —
        // the markdown renderer can't clamp by line, so the teaser renders as plain text with
        // HTML/markdown markup stripped.
        Text(
            text = description.toPlainTextPreview(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = DESCRIPTION_PREVIEW_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (description.length > DESCRIPTION_EXPAND_THRESHOLD) {
        Text(
            text =
                stringResource(
                    if (isExpanded) Res.string.book_detail_read_less else Res.string.book_detail_read_more,
                ),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .padding(top = 4.dp)
                    .clickable(onClick = onToggleExpanded),
        )
    }
}

/**
 * Credits slot (optional), then the three classification facet rows — Genres, Tags, Moods.
 *
 * Each block is preceded by a [Spacing.sectionGap] spacer and a matching [SectionOverline].
 * All three chip rows render through the single [FacetChipRow] component, switched by
 * [BookFacet]; the tag row maps each chip label back to its [Tag] to preserve tag-click
 * navigation. The [isLoadingTags] guard keeps the Tags block visible while tags are fetched.
 */
@Composable
private fun AboutClassificationBlocks(
    creditsSlot: (@Composable () -> Unit)?,
    genres: List<String>,
    onGenreClick: ((String) -> Unit)?,
    tags: List<Tag>,
    moods: List<Mood>,
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
        FacetChipRow(
            labels = genres,
            facet = BookFacet.Genre,
            onClick = onGenreClick,
        )
    }

    if (tags.isNotEmpty() || isLoadingTags) {
        Spacer(modifier = Modifier.height(Spacing.sectionGap))
        SectionOverline(text = stringResource(Res.string.book_detail_tags))
        Spacer(modifier = Modifier.height(Spacing.titleGap))
        // Map display name → Tag so the unified row can keep tag-click navigation.
        val tagsByName = remember(tags) { tags.associateBy { it.displayName() } }
        FacetChipRow(
            labels = tags.map { it.displayName() },
            facet = BookFacet.Tag,
            onClick = { name -> tagsByName[name]?.let(onTagClick) },
        )
    }

    if (moods.isNotEmpty()) {
        Spacer(modifier = Modifier.height(Spacing.sectionGap))
        SectionOverline(text = stringResource(Res.string.book_detail_mood))
        Spacer(modifier = Modifier.height(Spacing.titleGap))
        FacetChipRow(
            labels = moods.map { it.name },
            facet = BookFacet.Mood,
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
