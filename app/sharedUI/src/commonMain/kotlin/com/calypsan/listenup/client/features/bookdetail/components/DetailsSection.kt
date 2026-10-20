package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.bookdetail.formatFileSize
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailRow
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_abridged
import listenup.composeapp.generated.resources.book_detail_added
import listenup.composeapp.generated.resources.book_detail_all_credits
import listenup.composeapp.generated.resources.book_detail_asin
import listenup.composeapp.generated.resources.book_detail_chapter_count
import listenup.composeapp.generated.resources.book_detail_chapter_count_one
import listenup.composeapp.generated.resources.book_detail_details
import listenup.composeapp.generated.resources.book_detail_files_audio
import listenup.composeapp.generated.resources.book_detail_genre
import listenup.composeapp.generated.resources.book_detail_genre_line
import listenup.composeapp.generated.resources.book_detail_isbn
import listenup.composeapp.generated.resources.book_detail_language
import listenup.composeapp.generated.resources.book_detail_length
import listenup.composeapp.generated.resources.book_detail_maps
import listenup.composeapp.generated.resources.book_detail_maps_count
import listenup.composeapp.generated.resources.book_detail_publisher
import listenup.composeapp.generated.resources.book_detail_released
import listenup.composeapp.generated.resources.book_detail_unabridged
import org.jetbrains.compose.resources.stringResource

/**
 * Book Detail's "Details" section (spec §3.2.1 D31), drawn from [rows] (`bookDetailRows`): Publisher, Released, Genre,
 * Language, Length, Added, ISBN, ASIN, then three links: **All credits** ([onAllCredits]), **Files & audio**
 * ([onFilesAndAudio]: the format rows live in its sheet) and **Maps & illustrations** ([onMaps], handed its row so the
 * caller can open one document at once or the list). A row whose datum is absent is not in [rows]; with no rows at
 * all the section draws nothing. Each row is value left, dim label right, with a divider between rows.
 */
@Composable
fun DetailsSection(
    rows: List<BookDetailRow>,
    onAllCredits: () -> Unit,
    onFilesAndAudio: () -> Unit,
    onMaps: (BookDetailRow.MapsAndIllustrations) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.book_detail_details),
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }.padding(bottom = 12.dp),
        )
        rows.forEachIndexed { index, row ->
            val cell = row.cell()
            val onClick: (() -> Unit)? =
                when (row) {
                    is BookDetailRow.AllCredits -> onAllCredits

                    is BookDetailRow.FilesAndAudio -> onFilesAndAudio

                    is BookDetailRow.MapsAndIllustrations -> { -> onMaps(row) }

                    is BookDetailRow.Publisher, is BookDetailRow.Released, is BookDetailRow.GenreLine,
                    is BookDetailRow.Language, is BookDetailRow.Length, is BookDetailRow.Added,
                    is BookDetailRow.Isbn, is BookDetailRow.Asin,
                    -> null
                }
            MetadataRow(value = cell.value, label = cell.label, onClick = onClick)
            if (index < rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** One Details row as words: its dim [label] and its [value]. */
private data class DetailCell(
    val label: String,
    val value: String,
)

@Composable
private fun BookDetailRow.cell(): DetailCell =
    when (this) {
        is BookDetailRow.Publisher -> {
            DetailCell(stringResource(Res.string.book_detail_publisher), name)
        }

        is BookDetailRow.Released -> {
            DetailCell(stringResource(Res.string.book_detail_released), year.toString())
        }

        is BookDetailRow.GenreLine -> {
            val edition = if (abridged) Res.string.book_detail_abridged else Res.string.book_detail_unabridged
            DetailCell(
                stringResource(Res.string.book_detail_genre),
                stringResource(Res.string.book_detail_genre_line, genreName, stringResource(edition)),
            )
        }

        is BookDetailRow.Language -> {
            DetailCell(stringResource(Res.string.book_detail_language), displayName)
        }

        is BookDetailRow.Length -> {
            val chapters =
                when (chapterCount) {
                    0 -> null
                    1 -> stringResource(Res.string.book_detail_chapter_count_one)
                    else -> stringResource(Res.string.book_detail_chapter_count, chapterCount)
                }
            DetailCell(
                stringResource(Res.string.book_detail_length),
                listOfNotNull(
                    DurationFormatter.hoursMinutesAlways(durationMs.milliseconds),
                    chapters,
                ).joinToString(" · "),
            )
        }

        is BookDetailRow.Added -> {
            DetailCell(stringResource(Res.string.book_detail_added), formatBookDay(epochMs))
        }

        is BookDetailRow.Isbn -> {
            DetailCell(stringResource(Res.string.book_detail_isbn), isbn)
        }

        is BookDetailRow.Asin -> {
            DetailCell(stringResource(Res.string.book_detail_asin), asin)
        }

        is BookDetailRow.AllCredits -> {
            DetailCell(stringResource(Res.string.book_detail_all_credits), count.toString())
        }

        is BookDetailRow.FilesAndAudio -> {
            DetailCell(
                stringResource(Res.string.book_detail_files_audio),
                listOfNotNull(format, bitrate).joinToString(" · "),
            )
        }

        is BookDetailRow.MapsAndIllustrations -> {
            val kind = format ?: stringResource(Res.string.book_detail_maps_count, count)
            DetailCell(stringResource(Res.string.book_detail_maps), "$kind · ${formatFileSize(totalBytes)}")
        }
    }

/** A Details row: emphasis value on the left, dim label on the right; a link row adds a chevron and is a button. */
@Composable
private fun MetadataRow(
    value: String,
    label: String,
    onClick: (() -> Unit)?,
) {
    val haptics = LocalHaptics.current
    val clickable =
        if (onClick != null) {
            Modifier.clickable(role = Role.Button) {
                haptics.press()
                onClick()
            }
        } else {
            Modifier
        }
    Row(
        modifier = Modifier.fillMaxWidth().then(clickable).padding(vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
