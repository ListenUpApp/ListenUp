package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.web.design.ProgressBar
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The strip that says the library is still arriving — Home's, and the Library page's.
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
