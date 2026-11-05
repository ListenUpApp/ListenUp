package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterMarkerTier
import com.calypsan.listenup.client.presentation.chaptereditor.PartSuggestion
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_marker_edit
import listenup.composeapp.generated.resources.chapter_editor_marker_edit_named
import listenup.composeapp.generated.resources.chapter_editor_suggestion_accept
import listenup.composeapp.generated.resources.chapter_editor_suggestion_body
import listenup.composeapp.generated.resources.chapter_editor_suggestion_dismiss
import listenup.composeapp.generated.resources.chapter_editor_suggestion_list_end
import listenup.composeapp.generated.resources.chapter_editor_suggestion_title
import org.jetbrains.compose.resources.stringResource

private val HEADER_MIN_HEIGHT = 48.dp

/**
 * What a marker header and the part offer do.
 *
 * @property onEdit open the sheet for the marker this chapter carries at this tier.
 * @property onAcceptSuggestion "Use as parts".
 * @property onDismissSuggestion "Not now".
 */
@Immutable
data class ChapterMarkerActions(
    val onEdit: (String, ChapterMarkerTier) -> Unit = { _, _ -> },
    val onAcceptSuggestion: () -> Unit = {},
    val onDismissSuggestion: () -> Unit = {},
)

/** The header rows [chapter] opens, Book above Part. Most chapters add nothing here. */
internal fun LazyListScope.chapterMarkerHeaders(
    chapter: Chapter,
    actions: ChapterMarkerActions,
) {
    ChapterMarkerTier.entries.reversed().forEach { tier ->
        tier.titleOn(chapter)?.let { title ->
            item(key = "marker-$tier-${chapter.id}") {
                ChapterMarkerHeader(
                    title = title,
                    tier = tier,
                    onEdit = { actions.onEdit(chapter.id, tier) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * A marker in the list: the whole row is the way into its sheet, so there is one large target
 * rather than a small button beside a label that looks tappable and is not.
 */
@Composable
private fun ChapterMarkerHeader(
    title: String,
    tier: ChapterMarkerTier,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val (container, content) = tier.headerColors()
    val editLabel = stringResource(Res.string.chapter_editor_marker_edit_named, title)

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = HEADER_MIN_HEIGHT)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .clickable(onClickLabel = editLabel) {
                haptics.press()
                onEdit()
            }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(Res.string.chapter_editor_marker_edit),
            style = MaterialTheme.typography.labelLarge,
            color = content,
        )
    }
}

/** Container and content colours: a Book sits a step above the Parts inside it. */
@Composable
private fun ChapterMarkerTier.headerColors(): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (this) {
        ChapterMarkerTier.Part -> colors.secondaryContainer to colors.onSecondaryContainer
        ChapterMarkerTier.Book -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
}

/** The offer to mark chapters that look like parts. It stays an offer until one of its buttons. */
@Composable
internal fun PartSuggestionCard(
    suggestion: PartSuggestion,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(ContentShapes.card)
            .background(colors.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.chapter_editor_suggestion_title, suggestion.titles.size),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(Res.string.chapter_editor_suggestion_body, spokenList(suggestion.titles)),
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.chapter_editor_suggestion_dismiss))
            }
            ListenUpButton(
                text = stringResource(Res.string.chapter_editor_suggestion_accept),
                onClick = onAccept,
                fillMaxWidth = false,
            )
        }
    }
}

/** "Part I, Part II and Part III". There are always at least two, or there is no offer. */
@Composable
private fun spokenList(titles: List<String>): String =
    stringResource(
        Res.string.chapter_editor_suggestion_list_end,
        titles.dropLast(1).joinToString(", "),
        titles.last(),
    )
