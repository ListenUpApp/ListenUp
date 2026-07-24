package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.playback.NowPlayingState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_close_book
import listenup.composeapp.generated.resources.player_collapse
import listenup.composeapp.generated.resources.player_go_to_author
import listenup.composeapp.generated.resources.player_go_to_author_multiple
import listenup.composeapp.generated.resources.player_go_to_book
import listenup.composeapp.generated.resources.player_go_to_narrator
import listenup.composeapp.generated.resources.player_go_to_narrator_multiple
import listenup.composeapp.generated.resources.player_go_to_series
import listenup.composeapp.generated.resources.player_more_options
import listenup.composeapp.generated.resources.player_now_playing_overline
import listenup.composeapp.generated.resources.player_now_playing_wide
import listenup.composeapp.generated.resources.player_open_pdf
import org.jetbrains.compose.resources.stringResource

/**
 * Top bar for the Now Playing screen.
 *
 * **Compact layout** (`wide = false`): a [keyboard_arrow_down] collapse button on the left, a
 * centred "NOW PLAYING" overline label, and a [more_vert] overflow menu on the right.
 *
 * **Wide layout** (`wide = true`): a 52 dp [keyboard_arrow_down] tonal collapse button on the
 * left, a two-line "Now playing" overline + book/series title column that fills remaining width,
 * a tonal cast [IconButton], and a [more_vert] overflow menu. Matches the [NowPlayingDesktop]
 * design reference header.
 *
 * The overflow menu mirrors the items from the existing private [NowPlayingTopBar] in
 * [NowPlayingScreen]: go to book, series (conditional), author(s), narrator(s), and close book.
 *
 * @param state Current [NowPlayingState.Active] used to gate series/author/narrator menu items.
 *   In wide mode, [NowPlayingState.Active.seriesName] is used as the subtitle when present;
 *   otherwise [NowPlayingState.Active.title] is used.
 * @param onCollapse Called when the collapse (chevron-down) button is tapped.
 * @param onGoToBook Called when "Go to Book" is selected.
 * @param onGoToSeries Called with the series id when "Go to Series" is selected.
 * @param onGoToContributor Called with a contributor id when a single-contributor item is selected.
 * @param onShowAuthorPicker Called when "Go to Author…" is selected and there are multiple authors.
 * @param onShowNarratorPicker Called when "Go to Narrator…" is selected and there are multiple narrators.
 * @param onCloseBook Called when "Close Book" is selected.
 * @param hasPdf When true, shows the "Open PDF" menu item in the overflow menu.
 * @param onOpenPdf Called when "Open PDF" is selected from the overflow menu.
 * @param wide When true, renders the desktop-style header with overline + title column and cast
 *   button instead of the compact centred label. Default false.
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
    hasPdf: Boolean = false,
    onOpenPdf: () -> Unit = {},
    wide: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    val overflowAnchor: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.player_more_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OverflowMenu(
                state = state,
                expanded = showMenu,
                onDismiss = { showMenu = false },
                onGoToBook = onGoToBook,
                onGoToSeries = onGoToSeries,
                onGoToContributor = onGoToContributor,
                onShowAuthorPicker = onShowAuthorPicker,
                onShowNarratorPicker = onShowNarratorPicker,
                onCloseBook = onCloseBook,
                hasPdf = hasPdf,
                onOpenPdf = onOpenPdf,
            )
        }
    }

    if (wide) {
        WideTopBar(
            state = state,
            onCollapse = onCollapse,
            overflowAnchor = overflowAnchor,
            modifier = modifier,
        )
    } else {
        CompactTopBar(
            onCollapse = onCollapse,
            overflowAnchor = overflowAnchor,
            modifier = modifier,
        )
    }
}

/** Desktop-style header: tonal collapse · "Now playing"/title column · cast · overflow. */
@Composable
private fun WideTopBar(
    state: NowPlayingState.Active,
    onCollapse: () -> Unit,
    overflowAnchor: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(84.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Collapse button — leading edge, 52 dp tonal square.
        Surface(
            onClick = onCollapse,
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(Res.string.player_collapse),
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // "Now playing" overline + book/series subtitle.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.player_now_playing_wide),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = state.seriesName ?: state.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Cast / route-picker button — framework MediaRouteButton (auto-hides when no devices).
        CastButton(modifier = Modifier.size(48.dp))

        overflowAnchor()
    }
}

/** Compact (phone) bar: collapse left, centred "NOW PLAYING" label, overflow right. */
@Composable
private fun CompactTopBar(
    onCollapse: () -> Unit,
    overflowAnchor: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onCollapse,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(Res.string.player_collapse),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            text = stringResource(Res.string.player_now_playing_overline),
            style =
                MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CastButton(modifier = Modifier.size(48.dp))
            overflowAnchor()
        }
    }
}

/** Shared overflow [DropdownMenu] content for both compact and wide [PlayerTopBar] variants. */
@Suppress("LongParameterList", "CognitiveComplexMethod")
@Composable
private fun OverflowMenu(
    state: NowPlayingState.Active,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onGoToBook: () -> Unit,
    onGoToSeries: (String) -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onCloseBook: () -> Unit,
    hasPdf: Boolean = false,
    onOpenPdf: () -> Unit = {},
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        // Go to Book — always present.
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.player_go_to_book)) },
            onClick = {
                onDismiss()
                onGoToBook()
            },
            leadingIcon = { Icon(Icons.Default.Book, contentDescription = null) },
        )

        // Open PDF — only when the current book has a PDF document.
        if (hasPdf) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.player_open_pdf)) },
                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                onClick = {
                    onOpenPdf()
                    onDismiss()
                },
            )
        }

        // Go to Series — only when the book belongs to a series.
        if (state.seriesId != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.player_go_to_series)) },
                onClick = {
                    onDismiss()
                    state.seriesId?.let { onGoToSeries(it) }
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
            )
        }

        // Go to Author(s) — shows picker label when multiple authors exist.
        if (state.authors.isNotEmpty()) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (state.hasMultipleAuthors) Res.string.player_go_to_author_multiple else Res.string.player_go_to_author,
                        ),
                    )
                },
                onClick = {
                    onDismiss()
                    if (state.hasMultipleAuthors) {
                        onShowAuthorPicker()
                    } else {
                        state.authors.firstOrNull()?.let { onGoToContributor(it.id) }
                    }
                },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            )
        }

        // Go to Narrator(s) — shows picker label when multiple narrators exist.
        if (state.narrators.isNotEmpty()) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (state.hasMultipleNarrators) {
                                Res.string.player_go_to_narrator_multiple
                            } else {
                                Res.string.player_go_to_narrator
                            },
                        ),
                    )
                },
                onClick = {
                    onDismiss()
                    if (state.hasMultipleNarrators) {
                        onShowNarratorPicker()
                    } else {
                        state.narrators.firstOrNull()?.let { onGoToContributor(it.id) }
                    }
                },
                leadingIcon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
            )
        }

        HorizontalDivider()

        // Close Book — ends the playback session.
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.player_close_book)) },
            onClick = {
                onDismiss()
                onCloseBook()
            },
            leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
        )
    }
}
