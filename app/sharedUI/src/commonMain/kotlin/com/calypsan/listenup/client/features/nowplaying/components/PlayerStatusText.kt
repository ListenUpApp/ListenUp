package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.player.SourceStatus
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_np_status_not_playable
import listenup.composeapp.generated.resources.player_np_status_offline
import listenup.composeapp.generated.resources.player_np_status_server_unreachable
import listenup.composeapp.generated.resources.player_np_status_streaming_transcoded
import org.jetbrains.compose.resources.stringResource

/** The status slot's words for this news (§3.1); a source with no news has no status to word. */
@Composable
internal fun SourceStatus.statusText(): String =
    when (this) {
        SourceStatus.Offline -> stringResource(Res.string.player_np_status_offline)
        SourceStatus.ServerUnreachable -> stringResource(Res.string.player_np_status_server_unreachable)
        SourceStatus.StreamingTranscoded -> stringResource(Res.string.player_np_status_streaming_transcoded)
        SourceStatus.NotPlayable -> stringResource(Res.string.player_np_status_not_playable)
    }
