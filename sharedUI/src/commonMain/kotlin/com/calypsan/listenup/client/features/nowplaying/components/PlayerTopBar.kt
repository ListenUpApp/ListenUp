package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.playback.NowPlayingState

/**
 * Top bar for the Now Playing screen.
 *
 * Compact layout: a [keyboard_arrow_down] collapse button on the left, a centered
 * "NOW PLAYING" overline label, and a [more_vert] overflow menu on the right.
 *
 * The overflow menu mirrors the items from the existing private [NowPlayingTopBar] in
 * [NowPlayingScreen]: go to book, series (conditional), author(s), narrator(s), and close book.
 *
 * @param state Current [NowPlayingState.Active] used to gate series/author/narrator menu items.
 * @param onCollapse Called when the collapse (chevron-down) button is tapped.
 * @param onGoToBook Called when "Go to Book" is selected.
 * @param onGoToSeries Called with the series id when "Go to Series" is selected.
 * @param onGoToContributor Called with a contributor id when a single-contributor item is selected.
 * @param onShowAuthorPicker Called when "Go to Author…" is selected and there are multiple authors.
 * @param onShowNarratorPicker Called when "Go to Narrator…" is selected and there are multiple narrators.
 * @param onCloseBook Called when "Close Book" is selected.
 * @param wide When true, the label is omitted so the bar is leaner (tablet/desktop sidebars).
 * @param modifier Optional layout modifier.
 */
@Suppress("LongParameterList", "CognitiveComplexMethod")
@Composable
fun PlayerTopBar(
    state: NowPlayingState.Active,
    onCollapse: () -> Unit,
    onGoToBook: () -> Unit,
    onGoToSeries: (String) -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onCloseBook: () -> Unit,
    wide: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Collapse button — left edge.
        IconButton(
            onClick = onCollapse,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Collapse player",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Centered "NOW PLAYING" overline — omitted in wide mode where the bar is minimised.
        if (!wide) {
            Text(
                text = "NOW PLAYING",
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // Overflow menu — right edge.
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                // Go to Book — always present.
                DropdownMenuItem(
                    text = { Text("Go to Book") },
                    onClick = {
                        showMenu = false
                        onGoToBook()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Book, contentDescription = null)
                    },
                )

                // Go to Series — only when the book belongs to a series.
                if (state.seriesId != null) {
                    DropdownMenuItem(
                        text = { Text("Go to Series") },
                        onClick = {
                            showMenu = false
                            state.seriesId?.let { onGoToSeries(it) }
                        },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null)
                        },
                    )
                }

                // Go to Author(s) — shows picker label when multiple authors exist.
                if (state.authors.isNotEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(if (state.hasMultipleAuthors) "Go to Author…" else "Go to Author")
                        },
                        onClick = {
                            showMenu = false
                            if (state.hasMultipleAuthors) {
                                onShowAuthorPicker()
                            } else {
                                state.authors.firstOrNull()?.let { onGoToContributor(it.id) }
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null)
                        },
                    )
                }

                // Go to Narrator(s) — shows picker label when multiple narrators exist.
                if (state.narrators.isNotEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(if (state.hasMultipleNarrators) "Go to Narrator…" else "Go to Narrator")
                        },
                        onClick = {
                            showMenu = false
                            if (state.hasMultipleNarrators) {
                                onShowNarratorPicker()
                            } else {
                                state.narrators.firstOrNull()?.let { onGoToContributor(it.id) }
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                        },
                    )
                }

                HorizontalDivider()

                // Close Book — ends the playback session.
                DropdownMenuItem(
                    text = { Text("Close Book") },
                    onClick = {
                        showMenu = false
                        onCloseBook()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Close, contentDescription = null)
                    },
                )
            }
        }
    }
}
