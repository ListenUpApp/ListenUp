package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.auth_sign_in
import listenup.composeapp.generated.resources.player_close_book
import listenup.composeapp.generated.resources.player_mini_busy_a11y
import listenup.composeapp.generated.resources.player_mini_keep_listening
import listenup.composeapp.generated.resources.player_mini_listen_again
import listenup.composeapp.generated.resources.player_mini_listen_again_a11y
import listenup.composeapp.generated.resources.player_mini_open_inbox
import listenup.composeapp.generated.resources.player_pause
import listenup.composeapp.generated.resources.player_play
import listenup.composeapp.generated.resources.player_retry
import org.jetbrains.compose.resources.stringResource

/** The busy indicator, at the play glyph's size. */
private val MiniBusyIndicatorSize = 24.dp

/**
 * The mini player's one main button, drawn from the shared [PrimaryControl] (spec §3.5, §4.6; the `Main`,
 * `Mini-AndroidTablet` and `States-Android` boards). Play and pause are a filled icon button in `primary`; a stall keeps
 * that button, its size, shape and colour, with the uncontained M3 loading indicator in the glyph's colour in place of
 * the glyph, still pressable to pause (Simon, 2026-10-27); a way out of a problem is a labelled button,
 * because a glyph alone does not say "Sign in".
 *
 * @param size The play/pause button's container: 56×48 on the phone bar, 72×56 on the tablet toolbar.
 * @param playLabel Overrides "Play" (the background refusal's "Resume").
 * @param busyLabel Overrides the busy control's "Loading. Pause" (a dropped connection's "Reconnecting. Pause").
 * @param onKeepListening Non-null while the sleep timer fades the book out: the button is then Keep listening, which
 * cancels the timer and restores full volume instead of pausing (Simon, 2026-10-14). Now Playing keeps its Pause; the
 * substitution is the mini player's alone.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MiniPrimaryButton(
    primary: PrimaryControl,
    bookTitle: String,
    onClick: () -> Unit,
    size: DpSize,
    modifier: Modifier = Modifier,
    playLabel: String? = null,
    busyLabel: String? = null,
    onKeepListening: (() -> Unit)? = null,
) {
    val haptics = LocalHaptics.current
    if (onKeepListening != null) {
        FilledTonalButton(
            onClick = {
                haptics.press()
                onKeepListening()
            },
            modifier = modifier.heightIn(min = MinTouchTarget),
        ) {
            Text(stringResource(Res.string.player_mini_keep_listening))
        }
        return
    }
    when (primary) {
        PrimaryControl.Pause, PrimaryControl.Play, PrimaryControl.TapToPlay -> {
            val isPause = primary == PrimaryControl.Pause
            FilledIconButton(
                onClick = {
                    haptics.toggle(on = !isPause)
                    onClick()
                },
                shapes = IconButtonDefaults.shapes(),
                modifier = modifier.size(size),
            ) {
                Icon(
                    imageVector = if (isPause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription =
                        if (isPause) {
                            stringResource(Res.string.player_pause)
                        } else {
                            playLabel ?: stringResource(Res.string.player_play)
                        },
                )
            }
        }

        PrimaryControl.Busy -> {
            val label = busyLabel ?: stringResource(Res.string.player_mini_busy_a11y)
            FilledIconButton(
                onClick = onClick,
                shapes = IconButtonDefaults.shapes(),
                modifier = modifier.size(size).semantics { contentDescription = label },
            ) {
                ListenUpLoadingIndicator(
                    size = MiniBusyIndicatorSize,
                    color = LocalContentColor.current,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }

        PrimaryControl.Retry -> {
            MiniWayOutButton(stringResource(Res.string.player_retry), onClick, modifier)
        }

        PrimaryControl.SignIn -> {
            MiniWayOutButton(stringResource(Res.string.auth_sign_in), onClick, modifier)
        }

        PrimaryControl.OpenInbox -> {
            MiniWayOutButton(stringResource(Res.string.player_mini_open_inbox), onClick, modifier)
        }

        PrimaryControl.ListenAgain -> {
            val label = stringResource(Res.string.player_mini_listen_again_a11y, bookTitle)
            FilledTonalButton(
                onClick = {
                    haptics.press()
                    onClick()
                },
                modifier = modifier.heightIn(min = MinTouchTarget).semantics { contentDescription = label },
            ) {
                Text(stringResource(Res.string.player_mini_listen_again))
            }
        }

        PrimaryControl.Close -> {
            IconButton(onClick = onClick, modifier = modifier.size(MinTouchTarget)) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.player_close_book))
            }
        }
    }
}

/** A way out of a problem (Retry, Sign in, Open Inbox): a filled button that says what it does. */
@Composable
private fun MiniWayOutButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHaptics.current
    Button(
        onClick = {
            haptics.press()
            onClick()
        },
        modifier = modifier.heightIn(min = MinTouchTarget),
    ) {
        Text(label)
    }
}
