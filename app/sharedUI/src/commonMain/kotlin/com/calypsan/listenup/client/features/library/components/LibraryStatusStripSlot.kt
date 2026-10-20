package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.presentation.home.groupedCount
import com.calypsan.listenup.client.presentation.library.LibraryStatusStrip
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_arriving_none_yet
import listenup.composeapp.generated.resources.library_arriving_so_far
import listenup.composeapp.generated.resources.library_arriving_so_far_one
import listenup.composeapp.generated.resources.library_scan_strip_progress
import listenup.composeapp.generated.resources.library_scan_strip_progress_new
import listenup.composeapp.generated.resources.library_scan_strip_title
import listenup.composeapp.generated.resources.library_setup_building_title
import org.jetbrains.compose.resources.stringResource

/**
 * The Library's one status-strip slot (spec §3.1.1 L27/L28): a scan, or a library still arriving, said above the
 * grid, in the Library's own role colours (N6). Pinned rather than scrolled, and a polite live region, so the news
 * stays where a reader can find it. The bar is linear: wavy progress belongs to the book playing now.
 * [LibraryStatusStrip.Hidden] draws nothing.
 */
@Composable
internal fun LibraryStatusStripSlot(
    strip: LibraryStatusStrip,
    modifier: Modifier = Modifier,
) {
    val words = stripWords(strip) ?: return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = ContentShapes.card,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(words.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = words.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val fraction = (strip as? LibraryStatusStrip.Scanning)?.progress?.progressFraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                )
            } else {
                LinearProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                )
            }
        }
    }
}

private class StripWords(
    val title: String,
    val detail: String,
)

@Composable
private fun stripWords(strip: LibraryStatusStrip): StripWords? =
    when (strip) {
        LibraryStatusStrip.Hidden -> {
            null
        }

        is LibraryStatusStrip.Scanning -> {
            StripWords(stringResource(Res.string.library_scan_strip_title), scanDetail(strip.progress))
        }

        is LibraryStatusStrip.Arriving -> {
            StripWords(stringResource(Res.string.library_setup_building_title), arrivingDetail(strip.booksSoFar))
        }
    }

@Composable
private fun scanDetail(scan: ScanProgressState): String =
    when {
        // Walking and grouping know no book total yet: the phase is all there is to say.
        scan.booksTotal <= 0 -> {
            scan.phaseDisplayName
        }

        scan.added > 0 -> {
            stringResource(
                Res.string.library_scan_strip_progress_new,
                groupedCount(scan.books),
                groupedCount(scan.booksTotal),
                scan.added,
            )
        }

        else -> {
            stringResource(Res.string.library_scan_strip_progress, groupedCount(scan.books), groupedCount(scan.booksTotal))
        }
    }

@Composable
private fun arrivingDetail(booksSoFar: Int): String =
    when (booksSoFar) {
        0 -> stringResource(Res.string.library_arriving_none_yet)
        1 -> stringResource(Res.string.library_arriving_so_far_one)
        else -> stringResource(Res.string.library_arriving_so_far, groupedCount(booksSoFar))
    }
