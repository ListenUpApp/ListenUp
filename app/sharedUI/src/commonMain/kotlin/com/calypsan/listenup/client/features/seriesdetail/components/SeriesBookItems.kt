package com.calypsan.listenup.client.features.seriesdetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.RestrictedBookMarker
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.HeroInk
import com.calypsan.listenup.client.domain.model.BookListItem
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.series_book_position
import listenup.composeapp.generated.resources.series_duration_finished
import listenup.composeapp.generated.resources.series_progress_duration
import org.jetbrains.compose.resources.stringResource

/** A book in the phone list: cover, "Book N", title, and progress or duration. */
@Composable
internal fun SeriesBookRow(
    book: BookListItem,
    positionLabel: String,
    finished: Boolean,
    progress: Float?,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val rowColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val titleColor = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val subColor =
        if (highlighted) {
            HeroInk.muted()
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(rowColor)
                .clickable {
                    haptics.press()
                    onClick()
                }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box {
            BookCoverImage(
                bookId = book.id.value,
                coverPath = book.coverPath,
                coverHash = book.coverHash,
                contentDescription = book.title,
                title = book.title,
                author = book.authors.firstOrNull()?.name,
                modifier = Modifier.size(68.dp).clip(MaterialTheme.shapes.small),
            )
            RestrictedBookMarker(
                bookId = book.id.value,
                compact = true,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )
            if (finished) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 5.dp, y = 5.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.series_book_position, positionLabel),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            if (progress != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                    )
                    Text(
                        text = stringResource(Res.string.series_progress_duration, (progress * 100).toInt(), book.formatDuration()),
                        style = MaterialTheme.typography.labelMedium,
                        color = subColor,
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    text =
                        if (finished) {
                            stringResource(Res.string.series_duration_finished, book.formatDuration())
                        } else {
                            book.formatDuration()
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = subColor,
                )
            }
        }

        BookRowAction(finished = finished, highlighted = highlighted)
    }
}

@Composable
private fun BookRowAction(
    finished: Boolean,
    highlighted: Boolean,
) {
    val bg = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val tint = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val icon =
        when {
            highlighted -> Icons.Default.GraphicEq
            finished -> Icons.Default.Replay
            else -> Icons.Default.PlayArrow
        }
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * Vertical cover card for a book in the wide series grid — the grid analogue of [SeriesBookRow].
 *
 * Cover (with a finished check or now-playing badge overlay), the "Book N" position label, the
 * title, and a progress bar or duration. Designed to flow in a [GridCells.Adaptive] grid so the
 * series reads as a shelf at expanded widths rather than crushing the horizontal rows.
 */
@Composable
internal fun SeriesBookCard(
    book: BookListItem,
    positionLabel: String,
    finished: Boolean,
    progress: Float?,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val cardColor =
        if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val titleColor =
        if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(cardColor)
                .clickable {
                    haptics.press()
                    onClick()
                }.padding(10.dp),
    ) {
        SeriesBookCardCover(book = book, finished = finished, highlighted = highlighted)

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(Res.string.series_book_position, positionLabel),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
        )
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        SeriesBookCardFooter(book = book, progress = progress, finished = finished, highlighted = highlighted)
    }
}

/** Square cover for [SeriesBookCard] with a finished-check or now-playing badge overlay. */
@Composable
private fun SeriesBookCardCover(
    book: BookListItem,
    finished: Boolean,
    highlighted: Boolean,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        BookCoverImage(
            bookId = book.id.value,
            coverPath = book.coverPath,
            coverHash = book.coverHash,
            contentDescription = book.title,
            title = book.title,
            author = book.authors.firstOrNull()?.name,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small),
        )
        RestrictedBookMarker(
            bookId = book.id.value,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )
        if (finished || highlighted) {
            val badgeBg =
                if (finished) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primary
            val badgeTint =
                if (finished) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimary
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(badgeBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (finished) Icons.Default.Check else Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = badgeTint,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/** Progress bar + percentage, or a plain duration line, for [SeriesBookCard]. */
@Composable
private fun SeriesBookCardFooter(
    book: BookListItem,
    progress: Float?,
    finished: Boolean,
    highlighted: Boolean,
) {
    val subColor =
        if (highlighted) {
            HeroInk.muted()
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.series_progress_duration, (progress * 100).toInt(), book.formatDuration()),
            style = MaterialTheme.typography.labelMedium,
            color = subColor,
            maxLines = 1,
        )
    } else {
        Text(
            text =
                if (finished) {
                    stringResource(Res.string.series_duration_finished, book.formatDuration())
                } else {
                    book.formatDuration()
                },
            style = MaterialTheme.typography.bodyMedium,
            color = subColor,
            maxLines = 1,
        )
    }
}
