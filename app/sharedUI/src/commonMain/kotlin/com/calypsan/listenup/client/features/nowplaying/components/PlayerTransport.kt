package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.PlayMorphButton
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_mini_busy_a11y
import listenup.composeapp.generated.resources.player_next_chapter
import listenup.composeapp.generated.resources.player_np_previous_chapter_hint
import listenup.composeapp.generated.resources.player_pause
import listenup.composeapp.generated.resources.player_play
import listenup.composeapp.generated.resources.player_previous_chapter
import listenup.composeapp.generated.resources.player_skip_backward
import listenup.composeapp.generated.resources.player_skip_forward
import org.jetbrains.compose.resources.stringResource

/** The tonal skip buttons' side (spec P16, P17). */
private val SkipSize = 56.dp

/**
 * Now Playing's transport (spec P14–P18, §3.3): one standard button group of Previous · back N · play · forward N ·
 * Next (m3.material.io/components/button-groups: standard groups for rows of actions). Previous is enabled in every
 * chapter and says it restarts the chapter first, but is off in a chapterless book; Next is off at the last chapter. The skips keep their slots in every state
 * and are disabled while there is no settled position (S12), so the group never re-flows under the thumb (C5).
 */
@Suppress("LongParameterList") // one callback per control the board draws
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerTransport(
    state: PlayerUiState.Loaded,
    onPrimary: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
    playSize: Dp = 96.dp,
) {
    val settings = state.settings
    val hint = stringResource(Res.string.player_np_previous_chapter_hint)
    val haptics = LocalHaptics.current
    val previous = {
        haptics.press()
        onPreviousChapter()
    }
    ButtonGroup(
        overflowIndicator = {},
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        customItem(
            buttonGroupContent = {
                IconButton(
                    onClick = previous,
                    // A chapterless book has no previous: it would only jump to 0:00.
                    enabled = state.canSkipToPreviousChapter,
                    shapes = IconButtonDefaults.shapes(),
                    modifier =
                        Modifier.semantics {
                            onClick(label = hint) {
                                previous()
                                true
                            }
                        },
                ) { Icon(Icons.Filled.SkipPrevious, stringResource(Res.string.player_previous_chapter)) }
            },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = {
                FilledTonalIconButton(
                    onClick = {
                        haptics.press()
                        onSkipBack()
                    },
                    enabled = state.showsSkipBack,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(SkipSize),
                ) {
                    Icon(
                        SkipGlyphs.backward(settings.skipBackSec),
                        stringResource(Res.string.player_skip_backward, settings.skipBackSec),
                    )
                }
            },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = {
                PlayMorphButton(
                    isPlaying = state.primary == PrimaryControl.Pause,
                    isBusy = state.primary == PrimaryControl.Busy,
                    playLabel = stringResource(Res.string.player_play),
                    pauseLabel = stringResource(Res.string.player_pause),
                    busyLabel = stringResource(Res.string.player_mini_busy_a11y),
                    onClick = onPrimary,
                    size = playSize,
                )
            },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = {
                FilledTonalIconButton(
                    onClick = {
                        haptics.press()
                        onSkipForward()
                    },
                    enabled = state.showsSkipBack,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(SkipSize),
                ) {
                    Icon(
                        SkipGlyphs.forward(settings.skipForwardSec),
                        stringResource(Res.string.player_skip_forward, settings.skipForwardSec),
                    )
                }
            },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = {
                IconButton(
                    onClick = {
                        haptics.press()
                        onNextChapter()
                    },
                    enabled = state.canSkipToNextChapter,
                    shapes = IconButtonDefaults.shapes(),
                ) { Icon(Icons.Filled.SkipNext, stringResource(Res.string.player_next_chapter)) }
            },
            menuContent = {},
        )
    }
}
