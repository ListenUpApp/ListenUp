package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.StatusChip
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.features.nowplaying.VolumeBoostPresets
import com.calypsan.listenup.client.features.nowplaying.WIDE_HEADER_HEIGHT_DP
import com.calypsan.listenup.client.presentation.player.AudioSource
import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import com.calypsan.listenup.client.presentation.player.PlayerMenus
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_boost_db
import listenup.composeapp.generated.resources.player_boost_off
import listenup.composeapp.generated.resources.player_close_book
import listenup.composeapp.generated.resources.player_collapse
import listenup.composeapp.generated.resources.player_go_to_author
import listenup.composeapp.generated.resources.player_go_to_author_multiple
import listenup.composeapp.generated.resources.player_go_to_book
import listenup.composeapp.generated.resources.player_go_to_series
import listenup.composeapp.generated.resources.player_keyboard_shortcuts
import listenup.composeapp.generated.resources.player_more_options
import listenup.composeapp.generated.resources.player_now_playing
import listenup.composeapp.generated.resources.player_np_volume_boost_row
import listenup.composeapp.generated.resources.player_open_pdf
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The More menu's corners (m3.material.io/components/menus). */
private val MenuShape = RoundedCornerShape(16.dp)

/** Clears the collapse and More buttons, so the centred status never runs under them. */
private val StatusSideClearance = 56.dp

/**
 * Now Playing's top bar (spec §3.1, P1, the status slot, P25).
 *
 * **Compact** (`wide = false`): Collapse on the left, the [StatusChip] centred, More on the right.
 * **Wide** (`wide = true`, `NP-AndroidTablet`, P3): Collapse · "Now playing" in `titleMedium` Medium · the status chip
 * · More, on a 56dp bar.
 *
 * More is the shared builder's menu ([PlayerMenus.moreMenu]): Go to book, Go to series, Go to author, Open PDF and
 * Volume boost, then Close book apart in the error colour (m3.material.io/components/menus). Wide layouts show the
 * series as a link above the title (P10) and the authors on the byline, so their menu leaves both out.
 */
@Composable
fun PlayerTopBar(
    state: PlayerUiState.Loaded,
    onCollapse: () -> Unit,
    onMenuItem: (PlayerMenuItem) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val status: @Composable () -> Unit = {
        StatusChip(text = state.source.statusText(), emphasized = state.source is AudioSource.Casting)
    }
    val more: @Composable () -> Unit = {
        MoreMenu(state = state, includesSeries = !wide, includesAuthor = !wide, onMenuItem = onMenuItem)
    }
    if (wide) {
        WideTopBar(onCollapse = onCollapse, status = status, more = more, modifier = modifier)
    } else {
        CompactTopBar(onCollapse = onCollapse, status = status, more = more, modifier = modifier)
    }
}

/** Compact (phone) bar: collapse left, the status centred, More right. */
@Composable
private fun CompactTopBar(
    onCollapse: () -> Unit,
    status: @Composable () -> Unit,
    more: @Composable () -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHaptics.current
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        IconButton(
            onClick = {
                haptics.press()
                onCollapse()
            },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(Res.string.player_collapse))
        }
        Box(Modifier.padding(horizontal = StatusSideClearance)) { status() }
        Box(Modifier.align(Alignment.CenterEnd)) { more() }
    }
}

/**
 * Wide header (the board): a standard collapse, "Now playing" in `titleMedium` Medium (m3.material.io/styles/typography/
 * type-scale-tokens: Medium for titles), the status and More. The screen's pane title carries the same words for
 * TalkBack, so the text is not a heading of its own.
 */
@Composable
private fun WideTopBar(
    onCollapse: () -> Unit,
    status: @Composable () -> Unit,
    more: @Composable () -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHaptics.current
    Row(
        modifier = modifier.fillMaxWidth().height(WIDE_HEADER_HEIGHT_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = {
                haptics.press()
                onCollapse()
            },
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(Res.string.player_collapse))
        }
        Text(
            text = stringResource(Res.string.player_now_playing),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        status()
        more()
    }
}

/** The ⋮ anchor and the shared More menu, its groups split by gap dividers. */
@Composable
private fun MoreMenu(
    state: PlayerUiState.Loaded,
    includesSeries: Boolean,
    includesAuthor: Boolean,
    onMenuItem: (PlayerMenuItem) -> Unit,
) {
    val haptics = LocalHaptics.current
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        IconButton(
            onClick = {
                haptics.press()
                expanded = true
            },
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.player_more_options))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MenuShape,
            containerColor = colors.surfaceContainer,
        ) {
            PlayerMenus.moreMenu(state, includesSeries, includesAuthor, includesShortcuts = false).forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                val itemColors =
                    if (group.isDestructive) {
                        MenuDefaults.itemColors(textColor = colors.error, leadingIconColor = colors.error)
                    } else {
                        MenuDefaults.itemColors()
                    }
                group.items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label()) },
                        leadingIcon = { Icon(item.icon(), contentDescription = null) },
                        colors = itemColors,
                        onClick = {
                            haptics.press()
                            expanded = false
                            onMenuItem(item)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerMenuItem.label(): String =
    when (this) {
        PlayerMenuItem.GoToBook -> {
            stringResource(Res.string.player_go_to_book)
        }

        is PlayerMenuItem.GoToSeries -> {
            stringResource(Res.string.player_go_to_series)
        }

        is PlayerMenuItem.GoToAuthor -> {
            stringResource(Res.string.player_go_to_author)
        }

        PlayerMenuItem.ChooseAuthor -> {
            stringResource(Res.string.player_go_to_author_multiple)
        }

        is PlayerMenuItem.OpenPdf -> {
            stringResource(Res.string.player_open_pdf)
        }

        is PlayerMenuItem.VolumeBoost -> {
            val value =
                VolumeBoostPresets.format(
                    db = boostDb,
                    offLabel = stringResource(Res.string.player_boost_off),
                    dbLabel = stringResource(Res.string.player_boost_db, boostDb.roundToInt()),
                )
            stringResource(Res.string.player_np_volume_boost_row, value)
        }

        // Never offered here (includesShortcuts = false: Android has no app shortcuts); named for exhaustiveness.
        PlayerMenuItem.KeyboardShortcuts -> {
            stringResource(Res.string.player_keyboard_shortcuts)
        }

        PlayerMenuItem.CloseBook -> {
            stringResource(Res.string.player_close_book)
        }
    }

private fun PlayerMenuItem.icon(): ImageVector =
    when (this) {
        PlayerMenuItem.GoToBook -> Icons.Default.Book
        is PlayerMenuItem.GoToSeries -> Icons.AutoMirrored.Filled.LibraryBooks
        is PlayerMenuItem.GoToAuthor, PlayerMenuItem.ChooseAuthor -> Icons.Default.Person
        is PlayerMenuItem.OpenPdf -> Icons.Default.Description
        is PlayerMenuItem.VolumeBoost -> Icons.AutoMirrored.Filled.VolumeUp
        PlayerMenuItem.KeyboardShortcuts -> Icons.Default.Keyboard
        PlayerMenuItem.CloseBook -> Icons.Default.Close
    }
