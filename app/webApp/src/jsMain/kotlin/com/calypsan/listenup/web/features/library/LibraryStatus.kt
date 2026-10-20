package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.presentation.library.LibraryStatusStrip
import com.calypsan.listenup.web.design.ProgressBar
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The strip that says the library is still arriving on Home. The Library page reads the shared decision through
 * [LibraryStripStatus].
 *
 * A first sync runs for minutes in a browser, and a server scan can run for longer; without this
 * both pages show a short or unchanging shelf with no explanation. One composable for both, so the
 * two cannot drift into describing the same scan differently.
 *
 * ⛔ The seed is [isBuilding] — the caller passes `isBuildingInitialLibrary`, **not** `isSyncing`.
 * `isSyncing` tracks the connection, which is `Connected` for the whole of an initial seed — so it
 * is *false* during precisely the window this strip exists for. `LibraryUiState.Loaded` carries
 * the same warning.
 *
 * A scan outranks the seed when both are live, because [ScanProgressState] can say what is
 * actually happening and how far along it is. Renders nothing when neither is.
 */
@Composable
internal fun LibraryStatus(
    scan: ScanProgressState?,
    isBuilding: Boolean,
    /** Where it sits, for the spacing only its host knows — Home's column already gaps it. */
    placement: String? = null,
    /** A host's own words in place of the scan's phase and the seed's line — Home's (spec H5). */
    words: ArrivingWords? = null,
) {
    when {
        words != null && (scan != null || isBuilding) -> {
            StatusStrip(placement) {
                Span(attrs = { classes("lib-status-t") }) { Text(words.title) }
                scan?.progressFraction?.let { fraction ->
                    ProgressBar(value = fraction, label = words.title, attrs = { classes("lib-status-track") })
                }
                Span(attrs = { classes("lib-status-sub") }) { Text(words.body) }
            }
        }

        scan != null -> {
            StatusStrip(placement) {
                Span(attrs = { classes("lib-status-t") }) { Text(scan.phaseDisplayName) }
                scan.progressFraction?.let { fraction ->
                    ProgressBar(
                        value = fraction,
                        label = scan.phaseDisplayName,
                        attrs = { classes("lib-status-track") },
                    )
                }
                scan.changesSummary?.let { summary ->
                    Span(attrs = { classes("lib-status-sub") }) { Text(summary) }
                }
            }
        }

        isBuilding -> {
            StatusStrip(placement) {
                Span(attrs = { classes("lib-status-t") }) { Text("Building your library…") }
                Span(attrs = { classes("lib-status-sub") }) { Text("Books appear as they arrive.") }
            }
        }

        else -> {
            Unit
        }
    }
}

/**
 * The Library page's one status strip, reading the shared decision (`LibraryUiState.Loaded.statusStrip`) so a scan
 * and a first sync read the same in the browser as on Android and iOS: "Scanning your server · 412 of 1,280 · 3 new",
 * or "Building your library" with what Room holds so far. A first catch-up on a fresh device counts as arriving,
 * which `isBuildingInitialLibrary` alone missed. Renders nothing for [LibraryStatusStrip.Hidden].
 */
@Composable
internal fun LibraryStripStatus(
    strip: LibraryStatusStrip,
    placement: String? = null,
) {
    when (strip) {
        LibraryStatusStrip.Hidden -> {
            Unit
        }

        is LibraryStatusStrip.Scanning -> {
            val scan = strip.progress
            StatusStrip(placement) {
                Span(attrs = { classes("lib-status-t") }) { Text(SCANNING_TITLE) }
                Span(attrs = { classes("lib-status-sub") }) { Text(scanDetail(scan)) }
                ProgressBar(value = scan.progressFraction, label = SCANNING_TITLE, attrs = { classes("lib-status-track") })
            }
        }

        is LibraryStatusStrip.Arriving -> {
            StatusStrip(placement) {
                Span(attrs = { classes("lib-status-t") }) { Text(ARRIVING_TITLE) }
                Span(attrs = { classes("lib-status-sub") }) { Text(arrivingDetail(strip.booksSoFar)) }
                ProgressBar(value = null, label = ARRIVING_TITLE, attrs = { classes("lib-status-track") })
            }
        }
    }
}

/** "412 of 1,280 · 3 new"; the phase alone while the scan knows no book total yet. */
private fun scanDetail(scan: ScanProgressState): String =
    when {
        scan.booksTotal <= 0 -> scan.phaseDisplayName
        scan.added > 0 -> "${groupedCount(scan.books)} of ${groupedCount(scan.booksTotal)} · ${scan.added} new"
        else -> "${groupedCount(scan.books)} of ${groupedCount(scan.booksTotal)}"
    }

/** What Room holds so far; the client never knows the server's total during a sync pull, so there is no "of N". */
private fun arrivingDetail(booksSoFar: Int): String =
    when (booksSoFar) {
        0 -> "Books appear here as they arrive."
        1 -> "1 book so far. $ARRIVING_TAIL"
        else -> "${groupedCount(booksSoFar)} books so far. $ARRIVING_TAIL"
    }

private const val SCANNING_TITLE = "Scanning your server"
private const val ARRIVING_TITLE = "Building your library"
private const val ARRIVING_TAIL = "Books appear here as they arrive; you can start listening now."

/** The strip's box: a polite live region (spec §8.2), so progress is announced without stealing focus. */
@Composable
private fun StatusStrip(
    placement: String?,
    content: @Composable () -> Unit,
) {
    Div(attrs = {
        classes("lib-status")
        placement?.let { classes(it) }
        attr("role", "status")
    }) { content() }
}

/** A host's words for the strip: its [title] and the line under it. */
internal data class ArrivingWords(
    val title: String,
    val body: String,
)
