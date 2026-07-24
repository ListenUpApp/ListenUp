package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_chapters
import org.jetbrains.compose.resources.stringResource

private val tabularNumsStyle: TextStyle
    @Composable get() =
        MaterialTheme.typography.bodySmall.copy(
            fontFeatureSettings = "tnum",
        )

/**
 * Chapter list header showing the section title, a [CountBadge] with the total count, and an
 * optional trailing sort affordance slot.
 *
 * @param chapterCount Total number of chapters — displayed inside the count badge.
 * @param trailingContent Optional composable placed at the end of the header row (e.g. sort icon).
 */
@Composable
fun ChaptersHeader(
    chapterCount: Int,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.book_detail_chapters),
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
        )

        Spacer(modifier = Modifier.width(8.dp))

        CountBadge(count = chapterCount)

        Spacer(modifier = Modifier.weight(1f))

        trailingContent?.invoke()
    }
}

/**
 * Individual chapter list item showing number, title and duration.
 *
 * When [isCurrent] is `true` the row is highlighted in [MaterialTheme.colorScheme.primary]
 * (coral in the book-detail palette) and a [Icons.Default.GraphicEq] playing indicator is shown.
 * A [HorizontalDivider] using [MaterialTheme.colorScheme.outlineVariant] separates rows when
 * [showDivider] is `true` (pass `false` for the last visible row).
 *
 * @param chapter The chapter data.
 * @param chapterNumber 1-based chapter number for display.
 * @param isCurrent Whether this chapter is the one currently playing.
 * @param showDivider Whether to draw a bottom divider below this row.
 */
@Composable
fun ChapterListItem(
    chapter: ChapterUiModel,
    chapterNumber: Int,
    modifier: Modifier = Modifier,
    // TODO(book-detail): mark current chapter once progress→chapter mapping is available.
    isCurrent: Boolean = false,
    showDivider: Boolean = true,
) {
    val contentColor =
        if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val numberColor =
        if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    // [modifier] carries the row's horizontal inset so chapter rows align flush with the other
    // sections (the caller passes the screen margin on compact / the card's intra-card inset on wide).
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = chapterNumber.toString(),
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontFeatureSettings = "tnum",
                    ),
                color = numberColor,
                modifier = Modifier.widthIn(min = 28.dp),
            )

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = chapter.title,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (isCurrent) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = chapter.duration,
                style = tabularNumsStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}
